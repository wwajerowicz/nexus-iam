package ch.epfl.bluebrain.nexus.iam.directives

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives.AsyncAuthenticator
import akka.http.scaladsl.server.directives.Credentials
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.{AuthToken, Caller}
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.Future

object AuthDirectives {

  /**
    * Attempts to extract the Credentials from the HTTP call and generate
    * a [[Caller]] from it, using the ''realms''
    *
    * @param realms the surface API for realms, which provides the Caler given the token
    */
  def authenticator(realms: Realms[Task])(implicit s: Scheduler): AsyncAuthenticator[Caller] = {
    case Credentials.Missing => Future.successful(None)
    case Credentials.Provided(token) =>
      val cred = OAuth2BearerToken(token)
      realms.caller(AuthToken(cred.token)).runToFuture
  }
}
