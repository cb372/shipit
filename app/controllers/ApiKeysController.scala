package controllers

import java.util.UUID

import apikeys.{ApiKeys, NewApiKey}
import cats.data.EitherT
import cats.instances.future._
import cats.syntax.functor._
import com.gu.googleauth.{AuthAction, GoogleAuthConfig, UserIdentity}
import play.api.data.Forms._
import play.api.data._
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

class ApiKeysController(
    controllerComponents: ControllerComponents,
    authAction: AuthAction[AnyContent],
    val authConfig: GoogleAuthConfig,
    val wsClient: WSClient,
    apiKeys: ApiKeys[Future]
) extends AbstractController(controllerComponents) {
  import ApiKeysController._

  def list(page: Int): Action[AnyContent] =
    authAction.async { implicit request =>
      implicit val user: UserIdentity = request.user
      apiKeys.list(page).map(keys => Ok(views.html.apikeys.list(keys)))
    }

  def create: Action[AnyContent] =
    authAction.async { implicit request =>
      implicit val user: UserIdentity = request.user
      EitherT
        .fromEither[Future](CreateKeyForm.bindFromRequest().fold(Left(_), Right(_)))
        .leftMap(_ => Redirect(routes.ApiKeysController.list()).flashing("error" -> "Invalid request"))
        .semiflatMap(data => apiKeys.create(NewApiKey(UUID.randomUUID.toString, data.description, user.email)))
        .map(k => Redirect(routes.ApiKeysController.list()).flashing("info" -> s"Created API key: ${k.value.key}"))
        .merge
    }

  def disable(keyId: String): Action[AnyContent] =
    authAction.async { request =>
      implicit val user: UserIdentity = request.user
      apiKeys
        .disable(keyId)
        .as(Redirect(routes.ApiKeysController.list()).flashing("info" -> "Disabled API key"))
    }

  def enable(keyId: String): Action[AnyContent] =
    authAction.async { request =>
      implicit val user: UserIdentity = request.user
      apiKeys
        .enable(keyId)
        .as(Redirect(routes.ApiKeysController.list()).flashing("info" -> "Enabled API key"))
    }

  def delete(keyId: String): Action[AnyContent] =
    authAction.async { request =>
      implicit val user: UserIdentity = request.user
      apiKeys.delete(keyId).map {
        case true  => Redirect(routes.ApiKeysController.list()).flashing("info"  -> "Deleted API key")
        case false => Redirect(routes.ApiKeysController.list()).flashing("error" -> "Failed to delete API key")
      }
    }
}

object ApiKeysController {

  case class CreateKeyFormData(description: Option[String])

  val CreateKeyForm: Form[CreateKeyFormData] =
    Form(mapping("description" -> optional(text))(CreateKeyFormData)(CreateKeyFormData.unapply))
}
