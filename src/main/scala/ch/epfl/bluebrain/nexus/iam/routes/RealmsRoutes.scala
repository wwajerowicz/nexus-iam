package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.config.AppConfig.tracing._
import ch.epfl.bluebrain.nexus.iam.config.Contexts.{iamCtxUri, resourceCtxUri, searchCtxUri}
import ch.epfl.bluebrain.nexus.iam.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.iam.directives.AuthDirectives.authenticator
import ch.epfl.bluebrain.nexus.iam.directives.RealmDirectives._
import ch.epfl.bluebrain.nexus.iam.marshallers.instances._
import ch.epfl.bluebrain.nexus.iam.realms.{Realms, Resource, ResourceMetadata}
import ch.epfl.bluebrain.nexus.iam.routes.RealmsRoutes.Realm
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.ResourceF.resourceMetaEncoder
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.instances._
import ch.epfl.bluebrain.nexus.rdf.syntax._
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

/**
  * The realms routes.
  *
  * @param realms the realms api
  */
class RealmsRoutes(realms: Realms[Task])(implicit http: HttpConfig) {

  private implicit val resourceEncoder: Encoder[Resource] =
    Encoder.encodeJson.contramap { r =>
      resourceMetaEncoder.apply(r.discard) deepMerge r.value.fold(_.asJson, _.asJson)
    }

  private implicit val resourceMetadataEncoder: Encoder[ResourceMetadata] =
    Encoder.encodeJson.contramap { r =>
      resourceMetaEncoder.apply(r.discard) deepMerge Json.obj(
        nxv.label.prefix      -> Json.fromString(r.value._1.value),
        nxv.deprecated.prefix -> Json.fromBoolean(r.value._2),
      )
    }

  private implicit val resourceListEncoder: Encoder[List[Resource]] =
    Encoder.encodeJson.contramap[List[Resource]] { l =>
      Json
        .obj(nxv.total.prefix   -> Json.fromInt(l.size),
             nxv.results.prefix -> Json.arr(l.map(r => resourceEncoder(r).removeKeys("@context")): _*))
        .addContext(resourceCtxUri)
        .addContext(iamCtxUri)
        .addContext(searchCtxUri)
    }

  def routes: Route =
    pathPrefix("realms") {
      authenticateOAuth2Async("*", authenticator(realms)).withAnonymousUser(Caller.anonymous) { implicit caller =>
        concat(
          (get & pathEndOrSingleSlash) {
            trace("listRealms") {
              complete(realms.list.runToFuture)
            }
          },
          (put & label & pathEndOrSingleSlash) { id =>
            parameter("rev".as[Long].?) {
              case Some(rev) =>
                entity(as[Realm]) {
                  case Realm(name, openIdConfig, logo) =>
                    trace("updateRealm") {
                      complete(realms.update(id, rev, name, openIdConfig, logo).runToFuture)
                    }
                }
              case None =>
                entity(as[Realm]) {
                  case Realm(name, openIdConfig, logo) =>
                    trace("createRealm") {
                      complete(realms.create(id, name, openIdConfig, logo).runWithStatus(StatusCodes.Created))
                    }
                }
            }
          },
          (get & label & pathEndOrSingleSlash) { id =>
            parameter("rev".as[Long].?) {
              case Some(rev) =>
                trace("getRealmByIdAndRev") {
                  complete(realms.fetch(id, rev).runNotFound)
                }
              case None =>
                trace("getRealmById") {
                  complete(realms.fetch(id).runNotFound)
                }
            }
          },
          (delete & label & pathEndOrSingleSlash) { id =>
            parameter("rev".as[Long]) { rev =>
              trace("deprecateRealm") {
                complete(realms.deprecate(id, rev).runToFuture)
              }
            }
          }
        )
      }
    }
}

object RealmsRoutes {

  private[routes] final case class Realm(name: String, openIdConfig: Url, logo: Option[Url])
  private[routes] object Realm {
    implicit val realmDecoder: Decoder[Realm] = deriveDecoder[Realm]
  }
}
