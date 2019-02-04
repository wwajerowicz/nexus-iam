package ch.epfl.bluebrain.nexus.iam.realms

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.{Monad, MonadError}
import cats.data.EitherT
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.auth.TokenRejection._
import ch.epfl.bluebrain.nexus.iam.auth.{AccessToken, TokenRejection}
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, RealmsConfig}
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter
import ch.epfl.bluebrain.nexus.iam.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.iam.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.iam.realms.RealmState.{Active, Current, Deprecated, Initial}
import ch.epfl.bluebrain.nexus.iam.realms.Realms.next
import ch.epfl.bluebrain.nexus.iam.types.IamError._
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.iam.types._
import ch.epfl.bluebrain.nexus.rdf.Iri.{Path, Url}
import ch.epfl.bluebrain.nexus.service.indexer.cache.KeyValueStoreError._
import ch.epfl.bluebrain.nexus.service.indexer.cache.{KeyValueStore, KeyValueStoreConfig, KeyValueStoreError}
import ch.epfl.bluebrain.nexus.service.indexer.persistence.OffsetStorage.Volatile
import ch.epfl.bluebrain.nexus.service.indexer.persistence.{IndexerConfig, SequentialTagIndexer}
import ch.epfl.bluebrain.nexus.sourcing.akka.{AkkaAggregate, Retry}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Try

/**
  * Realms API.
  *
  * @param agg   the realms aggregate
  * @param acls  a lazy acls api
  * @param index an index implementation for realms
  * @tparam F    the effect type
  */
class Realms[F[_]: MonadThrowable](agg: Agg[F], acls: F[Acls[F]], index: RealmIndex[F])(implicit http: HttpConfig) {

  private val F = implicitly[MonadThrowable[F]]

  /**
    * Creates a new realm using the provided configuration.
    *
    * @param id           the realm id
    * @param name         the name of the realm
    * @param openIdConfig the address of the openid configuration
    * @param logo         an optional realm logo
    */
  def create(
      id: Label,
      name: String,
      openIdConfig: Url,
      logo: Option[Url]
  )(implicit caller: Caller): F[MetaOrRejection] =
    check(write) *> eval(CreateRealm(id, name, openIdConfig, logo, caller.subject)) <* updateIndex(id)

  /**
    * Updates an existing realm using the provided configuration.
    *
    * @param id           the realm id
    * @param rev          the current revision of the realm
    * @param name         the new name for the realm
    * @param openIdConfig the new openid configuration address
    * @param logo         an optional new logo
    */
  def update(
      id: Label,
      rev: Long,
      name: String,
      openIdConfig: Url,
      logo: Option[Url]
  )(implicit caller: Caller): F[MetaOrRejection] =
    check(id, write) *> eval(UpdateRealm(id, rev, name, openIdConfig, logo, caller.subject)) <* updateIndex(id)

  /**
    * Deprecates an existing realm. A deprecated realm prevents clients from authenticating.
    *
    * @param id  the id of the realm
    * @param rev the revision of the realm
    */
  def deprecate(id: Label, rev: Long)(implicit caller: Caller): F[MetaOrRejection] =
    check(id, write) *> eval(DeprecateRealm(id, rev, caller.subject)) <* updateIndex(id)

  /**
    * Fetches a realm.
    *
    * @param id the id of the realm
    * @return the realm in a Resource representation, None otherwise
    */
  def fetch(id: Label)(implicit caller: Caller): F[OptResource] =
    check(id, read) *> fetchUnsafe(id)

  /**
    * Fetches a realm at a specific revision.
    *
    * @param id  the id of the realm
    * @param rev the revision of the realm
    * @return the realm in a Resource representation, None otherwise
    */
  def fetch(id: Label, rev: Long)(implicit caller: Caller): F[OptResource] =
    check(id, read) *> fetchUnsafe(id, Some(rev))

  /**
    * @return the current realms sorted by their creation date.
    */
  def list(implicit caller: Caller): F[List[Resource]] =
    check(read) *> index.values.map(set => set.toList.sortBy(_.createdAt.toEpochMilli))

  /**
    * Attempts to compute the caller from the given [[AccessToken]].
    *
    * @param token the provided token
    * @return a caller reference if the token is valid, or an error in the F context otherwise
    */
  def caller(token: AccessToken): F[Caller] = {
    def jwt: Either[TokenRejection, SignedJWT] =
      Either
        .catchNonFatal(SignedJWT.parse(token.value))
        .leftMap(_ => InvalidAccessTokenFormat)
    def claims(jwt: SignedJWT): Either[TokenRejection, JWTClaimsSet] =
      Try(jwt.getJWTClaimsSet).filter(_ != null).toEither.leftMap(_ => InvalidAccessTokenFormat)
    def issuer(claimsSet: JWTClaimsSet): Either[TokenRejection, String] =
      Option(claimsSet.getIssuer).map(Right.apply).getOrElse(Left(AccessTokenDoesNotContainAnIssuer))
    def activeRealm(issuer: String): F[Either[TokenRejection, ActiveRealm]] =
      index.values
        .map {
          _.foldLeft(None: Option[ActiveRealm]) {
            case (s @ Some(_), _) => s
            case (acc, e)         => e.value.fold(_ => acc, ar => if (ar.issuer == issuer) Some(ar) else acc)
          }.toRight(UnknownAccessTokenIssuer)
        }
    def valid(jwt: SignedJWT, jwks: JWKSet): Either[TokenRejection, JWTClaimsSet] = {
      val proc        = new DefaultJWTProcessor[SecurityContext]
      val keySelector = new JWSVerificationKeySelector(JWSAlgorithm.RS256, new ImmutableJWKSet[SecurityContext](jwks))
      proc.setJWSKeySelector(keySelector)
      Either
        .catchNonFatal(proc.process(jwt, null))
        .leftMap(_ => TokenRejection.InvalidAccessToken)
    }
    def caller(claimsSet: JWTClaimsSet, realmId: Label): Either[TokenRejection, Caller] = {
      val authenticated     = Authenticated(realmId.value)
      val preferredUsername = Try(claimsSet.getStringClaim("preferred_username")).filter(_ != null).toOption
      val subject           = (preferredUsername orElse Option(claimsSet.getSubject)).toRight(AccessTokenDoesNotContainSubject)
      val groups = Try(claimsSet.getStringArrayClaim("groups"))
        .filter(_ != null)
        .recoverWith { case _ => Try(claimsSet.getStringClaim("groups").split(",").map(_.trim)) }
        .toOption
        .map(_.toSet)
        .getOrElse(Set.empty)
      subject.map { sub =>
        val user                    = User(sub, realmId.value)
        val groupSet: Set[Identity] = groups.map(g => Group(g, realmId.value))
        Caller(user, groupSet + Anonymous + user + authenticated)
      }
    }

    val triple: Either[TokenRejection, (SignedJWT, JWTClaimsSet, String)] = for {
      signed <- jwt
      cs     <- claims(signed)
      iss    <- issuer(cs)
    } yield (signed, cs, iss)

    val eitherCaller = for {
      t <- EitherT.fromEither[F](triple)
      (signed, cs, iss) = t
      realm  <- EitherT(activeRealm(iss))
      _      <- EitherT.fromEither[F](valid(signed, realm.keySet))
      result <- EitherT.fromEither[F](caller(cs, realm.id))
    } yield result

    eitherCaller.value.flatMap {
      case Right(c) => F.pure(c)
      case Left(tr) => F.raiseError(IamError.InvalidAccessToken(tr))
    }
  }

  private def fetchUnsafe(id: Label, optRev: Option[Long] = None): F[OptResource] =
    optRev
      .map { rev =>
        agg
          .foldLeft[State](id.value, Initial) {
            case (state, event) if event.rev <= rev => next(state, event)
            case (state, _)                         => state
          }
          .map {
            case Initial if rev != 0L       => None
            case c: Current if rev != c.rev => None
            case other                      => other.optResource
          }
      }
      .getOrElse(agg.currentState(id.value).map(_.optResource))

  private def check(id: Label, permission: Permission)(implicit caller: Caller): F[Unit] =
    acls
      .flatMap(_.hasPermission(id.toPath, permission))
      .ifM(F.unit, F.raiseError(AccessDenied(id.toIri(http.realmsIri), permission)))

  private def check(permission: Permission)(implicit caller: Caller): F[Unit] =
    acls
      .flatMap(_.hasPermission(Path./, permission, ancestors = false))
      .ifM(F.unit, F.raiseError(AccessDenied(http.realmsIri, permission)))

  private def eval(cmd: Command): F[MetaOrRejection] =
    agg
      .evaluateS(cmd.id.value, cmd)
      .flatMap {
        case Left(rej) => F.pure(Left(rej))
        // $COVERAGE-OFF$
        case Right(Initial) => F.raiseError(UnexpectedInitialState(cmd.id.toIri(http.realmsIri)))
        // $COVERAGE-ON$
        case Right(c: Current) => F.pure(Right(c.resourceMetadata))
      }

  private[realms] def updateIndex(id: Label): F[Unit] =
    fetchUnsafe(id).flatMap {
      case Some(res) => index.put(id, res)
      case None      => F.unit
    }
}

object Realms {

  private def mapError(cacheError: KeyValueStoreError): IamError =
    cacheError match {
      case e: ReadWriteConsistencyTimeout =>
        OperationTimedOut(s"Timeout while interacting with the cache due to '${e.timeout}'")
      case e: DistributedDataError => InternalError(e.reason)
    }

  /**
    * Creates a new realm index.
    */
  def index[F[_]: Timer](implicit as: ActorSystem, rc: RealmsConfig, F: Async[F]): RealmIndex[F] = {
    implicit val cfg: KeyValueStoreConfig = rc.keyValueStore
    implicit val monadError: MonadError[F, IamError] = new MonadError[F, IamError] {
      override def handleErrorWith[A](fa: F[A])(f: IamError => F[A]): F[A] = F.recoverWith(fa) {
        case t: IamError => f(t)
      }
      override def raiseError[A](e: IamError): F[A]                    = F.raiseError(e)
      override def pure[A](x: A): F[A]                                 = F.pure(x)
      override def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]         = F.flatMap(fa)(f)
      override def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
    }
    val clock: (Long, Resource) => Long = (_, resource) => resource.rev
    KeyValueStore.distributed("realms", clock, mapError)
  }

  /**
    * Constructs a new realms aggregate.
    */
  def aggregate[F[_]: Effect: Timer: Clock](
      implicit as: ActorSystem,
      mt: ActorMaterializer,
      rc: RealmsConfig,
      hc: HttpClient[F, Json]
  ): F[Agg[F]] =
    AkkaAggregate.sharded[F](
      "realms",
      RealmState.Initial,
      next,
      evaluate[F],
      rc.sourcing.passivationStrategy(),
      Retry(rc.sourcing.retry.retryStrategy),
      rc.sourcing.akkaSourcingConfig,
      rc.sourcing.shards
    )

  /**
    * Creates a new realms api using the provided aggregate, a lazy reference to the ACL api and a realm index reference.
    *
    * @param agg   the permissions aggregate
    * @param acls  a lazy reference to the ACL api
    * @param index a realm index reference
    */
  def apply[F[_]: MonadThrowable](
      agg: Agg[F],
      acls: F[Acls[F]],
      index: RealmIndex[F]
  )(implicit http: HttpConfig): Realms[F] =
    new Realms(agg, acls, index)

  /**
    * Creates a new permissions api using the default aggregate and a lazy reference to the ACL api.
    *
    * @param acls a lazy reference to the ACL api
    */
  def apply[F[_]: Effect: Timer: Clock](acls: F[Acls[F]])(
      implicit
      as: ActorSystem,
      mt: ActorMaterializer,
      http: HttpConfig,
      hc: HttpClient[F, Json],
      rc: RealmsConfig
  ): F[Realms[F]] =
    delay(aggregate, acls, index)

  /**
    * Creates a new realms api using the provided aggregate, a lazy reference to the ACL api and a realm index.
    *
    * @param agg  a lazy reference to the permissions aggregate
    * @param acls a lazy reference to the ACL api
    * @param index a realm index reference
    */
  def delay[F[_]: MonadThrowable](
      agg: F[Agg[F]],
      acls: F[Acls[F]],
      index: RealmIndex[F]
  )(implicit http: HttpConfig): F[Realms[F]] =
    agg.map(apply(_, acls, index))

  /**
    * Builds a process for automatically updating the realm index with the latest events logged.
    *
    * @param realms the realms API
    */
  def indexer[F[_]](realms: Realms[F])(implicit F: Effect[F],
                                       L: LiftIO[Task],
                                       as: ActorSystem,
                                       rc: RealmsConfig,
                                       sc: Scheduler): F[Unit] = {
    val indexFn = (events: List[Event]) => {
      val value = events.traverse(e => realms.updateIndex(e.id)) *> F.unit
      L.liftIO(value.toIO)
    }
    val cfg = IndexerConfig.builder
      .offset(Volatile)
      .name("realm-index")
      .batch(rc.indexing.batch, rc.indexing.batchTimeout)
      .plugin(rc.sourcing.queryJournalPlugin)
      .tag(TaggingAdapter.realmEventTag)
      .retry(rc.indexing.retry.retryStrategy)
      .index[Event](indexFn)
      .build
    F.delay(SequentialTagIndexer.start(cfg)) *> F.unit
  }

  private[realms] def next(state: State, event: Event): State = {
    // format: off
    def created(e: RealmCreated): State = state match {
      case Initial => Active(e.id, e.rev, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, e.instant, e.subject, e.instant, e.subject)
      case other   => other
    }
    def updated(e: RealmUpdated): State = state match {
      case s: Current => Active(e.id, e.rev, e.name, e.openIdConfig, e.issuer, e.keys, e.grantTypes, e.logo, e.authorizationEndpoint, e.tokenEndpoint, e.userInfoEndpoint, e.revocationEndpoint, e.endSessionEndpoint, s.createdAt, s.createdBy, e.instant, e.subject)
      case other      => other
    }
    def deprecated(e: RealmDeprecated): State = state match {
      case s: Active => Deprecated(e.id, e.rev, s.name, s.openIdConfig, s.logo, s.createdAt, s.createdBy, e.instant, e.subject)
      case other     => other
    }
    // format: on
    event match {
      case e: RealmCreated    => created(e)
      case e: RealmUpdated    => updated(e)
      case e: RealmDeprecated => deprecated(e)
    }
  }

  private def evaluate[F[_]: MonadThrowable: Clock: HttpJsonClient](state: State, cmd: Command): F[EventOrRejection] = {
    val F = implicitly[Monad[F]]
    val C = implicitly[Clock[F]]
    def instantF: F[Instant] =
      C.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)
    def accept(f: Instant => Event): F[EventOrRejection] =
      instantF.map { instant =>
        Right(f(instant))
      }
    def reject(rejection: Rejection): F[EventOrRejection] =
      F.pure(Left(rejection))
    // format: off
    def create(c: CreateRealm): F[EventOrRejection] = state match {
      case Initial =>
        for {
          instant  <- instantF
          wkeither <- WellKnown[F](c.openIdConfig)
        } yield wkeither.map { wk =>
          RealmCreated(c.id, 1L, c.name, c.openIdConfig, wk.issuer, wk.keys, wk.grantTypes, c.logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, instant, c.subject)
        }
      case _ => reject(RealmAlreadyExists(c.id))
    }
    def update(c: UpdateRealm): F[EventOrRejection] = state match {
      case Initial                      => reject(RealmNotFound(c.id))
      case s: Current if s.rev != c.rev => reject(IncorrectRev(c.rev, s.rev))
      case s: Current =>
        for {
          instant  <- instantF
          wkeither <- WellKnown[F](c.openIdConfig)
        } yield wkeither.map { wk =>
          RealmUpdated(c.id, s.rev + 1, c.name, c.openIdConfig, wk.issuer, wk.keys, wk.grantTypes, c.logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, instant, c.subject)
        }
    }
    def deprecate(c: DeprecateRealm): F[EventOrRejection] = state match {
      case Initial                      => reject(RealmNotFound(c.id))
      case s: Current if s.rev != c.rev => reject(IncorrectRev(c.rev, s.rev))
      case _: Deprecated                => reject(RealmAlreadyDeprecated(c.id))
      case s: Current                   => accept(RealmDeprecated(s.id, s.rev + 1, _, c.subject))
    }
    // format: on

    cmd match {
      case c: CreateRealm    => create(c)
      case c: UpdateRealm    => update(c)
      case c: DeprecateRealm => deprecate(c)
    }
  }
}
