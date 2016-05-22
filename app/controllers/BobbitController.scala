package controllers

import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import _root_.util.FutureO
import akka.actor.ActorSystem
import jobs._
import model.{Job}
import org.reactivecouchbase.client.OpResult
import play.api.http.HeaderNames
import play.api.{Configuration, Environment}
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import repository.{TubeRepository, BobbitRepository}
import service.{TokenService, BearerTokenGenerator, MailGunService, JobService}
import service.tfl.{TubeConnector, TubeService}
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import model._


class BobbitController @Inject()(system: ActorSystem, wsClient: WSClient, conf: Configuration) extends Controller with JsonParser with Securing {
  val repository: BobbitRepository = BobbitRepository

  def getTubRepository = TubeRepository

  object TokenService extends TokenService {
    override val bobbitRepository: BobbitRepository = repository
  }

  object JobServiceImpl extends JobService {
    val repo = repository
    val ws = wsClient
    val tubeRepository = getTubRepository
    override val configuration: Configuration = conf
    override val mailGunService: MailGunService = MailGunService
  }

  object MailGunService extends MailGunService {
    override val ws: WSClient = wsClient

    override def mailGunApiKey = conf.getString("mailgun-api-key").getOrElse(throw new IllegalStateException("no configuration found for mailGun apiKey"))

    override def mailGunHost: String = conf.getString("mailgun-host").getOrElse(throw new IllegalStateException("no configuration found for mailGun host"))

    override def enableSender: Boolean = conf.getBoolean("mailgun-enabled").getOrElse(false)

  }

  object TubeServiceRegistry extends TubeService with TubeConnector {
    val ws = wsClient
    val tubeRepository = getTubRepository
    override val configuration: Configuration = conf
  }


  lazy val tubeServiceActor = system.actorOf(TubeServiceFetchActor.props(TubeServiceRegistry), "tubeServiceActor")
  lazy val runningActor = system.actorOf(RunningJobActor.props(JobServiceImpl), "runningJobActor")
  lazy val resetRunningJobActor = system.actorOf(ResetRunningJobActor.props(JobServiceImpl), "resetRunningJobActor")
  lazy val alertJobActor = system.actorOf(ProcessAlertsJobActor.props(JobServiceImpl), "alertJobActor")


  lazy val tubeScheduleJob = system.scheduler.schedule(
    0.microseconds, 10000.milliseconds, tubeServiceActor, Run("run"))

  lazy val runningJobScheduleJob = system.scheduler.schedule(
    0.microseconds, 10000.milliseconds, runningActor, Run("run"))

  lazy val resetRunningJobScheduleJob = system.scheduler.schedule(
    0.microseconds, 10000.milliseconds, resetRunningJobActor, Run("run"))

  lazy val alertJobScheduleJob = system.scheduler.schedule(
    0.microseconds, 10000.milliseconds, alertJobActor, Run("run"))

  def fetchTubeLine() = Action.async { implicit request =>

    //      tubeScheduleJob
    runningJobScheduleJob
    resetRunningJobScheduleJob
    alertJobScheduleJob
    Future.successful(Ok(Json.obj("res" -> true)))

  }

  def find(id: String) = IsAuthenticated { implicit request =>
    repository.findJobById(id) map {
      case b: Some[Job] => Ok(Json.toJson[Job](b.get))
      case _ => NotFound
    }
  }

  def findAllJobByToken() = IsAuthenticated { implicit authContext =>
     repository.findAllJobByAccountId(authContext.token.accountId) map {
       case list:Seq[Job] => Ok(Json.toJson(list))
     }
  }

  def findAccountByToken() = IsAuthenticated { implicit authContext =>
    println(s"findAccountByToken $authContext")
    repository.findAccountById(authContext.token.accountId) map {
      case Some(account) =>  println("findAccountByToken2"); Ok(Json.toJson(account))
      case _ => NotFound
    }
  }

  def deleteAll() = Action.async {
    implicit request =>

      for {
        del <- repository.deleteAllAlerts()
        del <- repository.deleteAllJobs()
        del <- repository.deleteAllRunningJob()
        del <- repository.deleteAllAccount()
      } yield Ok
  }

  def delete(id: String) = Action.async {
    implicit request =>
      repository.deleteById(id) map {
        case Some(id) => Ok
        case _ => InternalServerError
      }
  }

  def deleteRunningJob(id: String) = Action.async {
    implicit request =>
      repository.deleteById(id) map {
        case Some(id) => Ok
        case _ => InternalServerError
      }
  }

  def findRunningJobByJobId(jobId: String) = IsAuthenticated { implicit request =>
    repository.findRunningJobByJobId(jobId) map {
      case b: Some[RunningJob] => Ok(Json.toJson[RunningJob](b.get))
      case _ => NotFound
    }
  }


  def findActiveRunningJob() = IsAuthenticated { implicit request =>
    JobServiceImpl.findAndProcessActiveJobs() map {
      case recs => Ok(Json.toJson[Seq[RunningJob]](recs))
    }
  }

  def findAccount(id: String) = IsAuthenticated { implicit authContext =>
      repository.findAccountById(id) map {
        case b: Some[Account] => Ok(Json.toJson[Account](b.get))
        case _ => NotFound
      }
  }


  def save() = IsAuthenticatedWithJson { implicit request =>
    val jobId = UUID.randomUUID().toString
    withJsonBody[Job]{ job =>
      val jobToSave = job.copy(id = Some(jobId))
      for {
        Some(id) <- repository.saveJob(jobToSave)
        runningId <- repository.saveRunningJob(RunningJob.fromJob(jobToSave))
      }  yield Created.withHeaders("Location" -> ("/api/bobbit/" + id))

    }
  }

  def checkUserName(userName: String) = IsAuthenticated {implicit request =>
    repository.findAccountByUserName(userName) map {
      case head :: tail => Ok
      case Nil => NotFound
    }
  }

  def validateAccount(token: String) = Action.async {
    val res = (for {
      tk <- FutureO(repository.findTokenBy(token))
      result <- FutureO(repository.activateAccount(tk, token))
    } yield result).future

    res map {
      case Some(id) => Redirect("/accountactive")
      case None => BadRequest(Json.obj("message" -> JsString(""))) //TODO
    }
  }

  def validateToken(token: String) = Action.async {
    TokenService.validateToken(token) map {
      case Some(token) => Ok
      case _ => BadRequest
    }
  }

  //TODO send email to confirm account
  def account() = Action.async(parse.json) { implicit request =>
    val accId = UUID.randomUUID().toString
    withJsonBody[Account]{ account =>
    val accountToSave = account.copy(id = Some(accId))
      for {
       account <- repository.saveAccount(accountToSave)
       token = BearerTokenGenerator.generateSHAToken("account-token")
       _ <- repository.saveToken(Token(token = token,accountId = accId))
      } yield Created.withHeaders(LOCATION -> ("/api/bobbit/account/" + accId),HeaderNames.AUTHORIZATION -> token )
    }
  }

  def login() = Action.async(parse.json) { implicit request =>
    withJsonBody[Login]{ login =>

       repository.findAccountByUserName(login.username) flatMap  {
            case Seq(account) if (account.active && account.password == login.password) => {
                       val token  = BearerTokenGenerator.generateSHAToken("account-token")
                       repository.saveToken(Token(token = token,accountId = account.getId)) map  {
                           case Some(id) => Ok.withCookies(Cookie("token",token,httpOnly = false))
                           case _ => println(s"Error saving token for account ${account.getId} in login");InternalServerError
                       }
                 }
            case Seq(account) => Future.successful(Unauthorized)
            case Nil => Future.successful(NotFound)
       }

    }

  }

  def logout() = Action.async { implicit request =>
    request.headers.get(HeaderNames.AUTHORIZATION) match {
      case Some(token) => TokenService.deleteToken(token) map {
        case _ => Ok.withCookies(Cookie("token","",httpOnly = false))
      }
      case _ => Future.successful(Ok.withCookies(Cookie("token","",httpOnly = false)))
    }
  }


}


