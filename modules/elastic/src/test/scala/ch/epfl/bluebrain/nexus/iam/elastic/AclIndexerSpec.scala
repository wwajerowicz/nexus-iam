package ch.epfl.bluebrain.nexus.iam.elastic

import java.net.URLEncoder
import java.time.Clock

import akka.testkit.TestKit
import cats.instances.future._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticFailure.ElasticClientError
import ch.epfl.bluebrain.nexus.commons.es.client.{ElasticClient, ElasticDecoder, ElasticQueryClient}
import ch.epfl.bluebrain.nexus.commons.es.server.embed.ElasticServer
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.{UntypedHttpClient, withAkkaUnmarshaller}
import ch.epfl.bluebrain.nexus.commons.iam.acls.Event._
import ch.epfl.bluebrain.nexus.commons.iam.acls.Path._
import ch.epfl.bluebrain.nexus.commons.iam.acls.Permission._
import ch.epfl.bluebrain.nexus.commons.iam.acls._
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity
import ch.epfl.bluebrain.nexus.commons.iam.identity.Identity.{Anonymous, GroupRef, UserRef}
import ch.epfl.bluebrain.nexus.commons.iam.io.serialization.SimpleIdentitySerialization._
import ch.epfl.bluebrain.nexus.commons.test.{Randomness, Resources}
import ch.epfl.bluebrain.nexus.commons.types.search.QueryResult.ScoredQueryResult
import ch.epfl.bluebrain.nexus.commons.types.search.{Pagination, QueryResults}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import io.circe.java8.time._
import io.circe.{Decoder, Json}
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}

import scala.concurrent.Future
import scala.concurrent.duration._

class AclIndexerSpec
    extends ElasticServer
    with WordSpecLike
    with Matchers
    with ScalaFutures
    with Randomness
    with Resources
    with Inspectors
    with BeforeAndAfterAll
    with Eventually
    with CancelAfterFailure
    with Assertions {

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(9 seconds, 400 milliseconds)

  private implicit val cl: UntypedHttpClient[Future]         = HttpClient.akkaHttpClient
  private implicit val D: Decoder[QueryResults[AclDocument]] = ElasticDecoder[AclDocument]
  private implicit val rsSearch: HttpClient[Future, QueryResults[AclDocument]] =
    withAkkaUnmarshaller[QueryResults[AclDocument]]
  private val client = ElasticClient[Future](esUri, ElasticQueryClient[Future](esUri))

  private val base     = s"http://127.0.0.1/v0"
  private val settings = ElasticConfig(base, genString(length = 6), genString(length = 6))

  private def getAll: Future[QueryResults[AclDocument]] =
    client.search[AclDocument](Json.obj("query" -> Json.obj("match_all" -> Json.obj())))(Pagination(0, 100))

  private def indexId(identity: Identity): String =
    URLEncoder.encode(s"${settings.indexPrefix}_${identity.id.show}", "UTF-8").toLowerCase

  private def genPath = genString(length = 4) / genString(length = 4) / genString(length = 4)

  private def permsResult(path: Path,
                          identity: Identity,
                          perms: Permissions,
                          created: Meta,
                          updated: Meta): ScoredQueryResult[AclDocument] =
    ScoredQueryResult(1F, AclDocument(path, identity, perms, created.instant, updated.instant))

  "An AclIndexer" should {
    val indexer                 = AclIndexer(client, settings)
    val userIdentity: Identity  = UserRef("realm", "user1")
    val groupIdentity: Identity = GroupRef("realm", "group1")
    val anon                    = Anonymous()
    val meta                    = Meta(Anonymous(), Clock.systemUTC.instant())
    val path1                   = genPath
    val path2                   = genPath
    val path3                   = genPath ++ genPath

    "index a PermissionsAdded event on a path" in {
      val event = PermissionsAdded(path1, AccessControlList(anon -> Permissions(Read)), meta)
      val index = indexId(anon)

      whenReady(client.existsIndex(index).failed) { e =>
        e shouldBe a[ElasticClientError]
      }
      indexer(event).futureValue
      eventually {
        val rs = getAll.futureValue
        rs.results.size shouldEqual 1
        rs.results.head.source shouldEqual AclDocument(path1, anon, Permissions(Read), meta.instant, meta.instant)
        client.existsIndex(index).futureValue shouldEqual (())
      }
    }

    val meta1 = Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong))
    "index another PermissionsAdded event on the same path" in {
      val event = PermissionsAdded(path1,
                                   AccessControlList(anon         -> Permissions(Read, Write, Own),
                                                     userIdentity -> Permissions(Read, Write)),
                                   meta1)
      val index = indexId(userIdentity)

      whenReady(client.existsIndex(index).failed) { e =>
        e shouldBe a[ElasticClientError]
      }
      indexer(event).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path1, anon, Permissions(Read, Write, Own), meta, meta1),
          permsResult(path1, userIdentity, Permissions(Read, Write), meta1, meta1)
        )
        client.existsIndex(index).futureValue shouldEqual (())
      }
    }

    val meta2 = Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong))
    "index another PermissionsAdded event on a separate path" in {
      val event  = PermissionsAdded(path2, AccessControlList(userIdentity  -> Permissions(Read, Write, Own)), meta2)
      val event2 = PermissionsAdded(path3, AccessControlList(groupIdentity -> Permissions(Read, Own)), meta2)
      val event3 = PermissionsAdded(path3, AccessControlList(anon          -> Permissions(Own, Write)), meta2)

      indexer(event).futureValue
      indexer(event2).futureValue
      indexer(event3).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path1, anon, Permissions(Read, Write, Own), meta, meta1),
          permsResult(path1, userIdentity, Permissions(Read, Write), meta1, meta1),
          permsResult(path2, userIdentity, Permissions(Read, Write, Own), meta2, meta2),
          permsResult(path3, groupIdentity, Permissions(Read, Own), meta2, meta2),
          permsResult(path3, anon, Permissions(Own, Write), meta2, meta2)
        )
      }
    }

    "index a PermissionsRemoved" in {
      val event =
        PermissionsRemoved(path3, anon, Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong)))
      indexer(event).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path1, anon, Permissions(Read, Write, Own), meta, meta1),
          permsResult(path1, userIdentity, Permissions(Read, Write), meta1, meta1),
          permsResult(path2, userIdentity, Permissions(Read, Write, Own), meta2, meta2),
          permsResult(path3, groupIdentity, Permissions(Read, Own), meta2, meta2)
        )
      }
    }

    "index a PermissionsCleared" in {
      val event = PermissionsCleared(path1, Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong)))
      indexer(event).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path2, userIdentity, Permissions(Read, Write, Own), meta2, meta2),
          permsResult(path3, groupIdentity, Permissions(Read, Own), meta2, meta2)
        )
      }
    }

    val meta3 = Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong))
    "index a PermissionsSubtracted" in {
      val event = PermissionsSubtracted(path2, userIdentity, Permissions(Read, Write, Permission("publish")), meta3)
      indexer(event).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path2, userIdentity, Permissions(Own), meta2, meta3),
          permsResult(path3, groupIdentity, Permissions(Read, Own), meta2, meta2)
        )
      }
    }

    val meta4 = Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong))
    "index another PermissionsSubtracted" in {
      indexer(PermissionsSubtracted(path2, userIdentity, Permissions(Write), meta4)).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path2, userIdentity, Permissions(Own), meta2, meta4),
          permsResult(path3, groupIdentity, Permissions(Read, Own), meta2, meta2)
        )
      }

      indexer(PermissionsSubtracted(path3, groupIdentity, Permissions(Read, Own), meta4)).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path2, userIdentity, Permissions(Own), meta2, meta4),
          permsResult(path3, groupIdentity, Permissions.empty, meta2, meta4)
        )
      }
    }

    "index another PermissionsAdded on an empty permissions list" in {
      val meta5 = Meta(Anonymous(), Clock.systemUTC.instant().plusMillis(genInt().toLong))
      val event = PermissionsAdded(path3, AccessControlList(groupIdentity -> Permissions(Read, Write, Own)), meta5)
      indexer(event).futureValue
      eventually {
        getAll.futureValue.results should contain theSameElementsAs List(
          permsResult(path2, userIdentity, Permissions(Own), meta2, meta4),
          permsResult(path3, groupIdentity, Permissions(Read, Write, Own), meta2, meta5)
        )
      }
    }
  }
}