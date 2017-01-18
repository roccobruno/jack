package controller

import java.util.UUID

import model._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, WithApplication}
import repository.BobbytRepository
import util.{Testing, TokenUtil}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


@RunWith(classOf[JUnitRunner])
class BobbytControllerSpec extends Specification  {

  lazy  val appEnableSec = GuiceApplicationBuilder().loadConfig(Configuration("security-enabled" -> true)).build()

  trait Setup extends WithApplication with TokenUtil  {
    val bobbytRepos = BobbytRepository

    def cleanUpDBAndCreateToken = {
      Await.result( for {
        del <-bobbytRepos.deleteAllToken()
        del <-bobbytRepos.deleteAllAccount()
        del <-bobbytRepos.deleteAllAlerts()
        del <-bobbytRepos.deleteAllJobs()
      } yield del, 10 seconds)

    }

    def login = {
      val resp = route(implicitApp, FakeRequest(POST, "/api/bobbyt/login-token").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")).withJsonBody(Json.parse("{}"))).get
      status(resp) must equalTo(CREATED)
    }

  }



  "bobbyt controller" should {

    val id = "12345"


    "return 201 when posting a bobbyt record" in new Setup {

      cleanUpDBAndCreateToken

      login

      private val id = UUID.randomUUID().toString
      private val job = Job("jobTitle",alert = Email("name",EmailAddress("from@mss.it"),"name",EmailAddress("from@mss.it")),
        journey= Journey(true,MeansOfTransportation(Seq(TubeLine("central","central")),Nil),TimeOfDay(8,30),40),accountId = "accountId")
      private val toJson = Json.toJson(job)

      val response = route(implicitApp, FakeRequest(POST, "/api/bobbyt").withBody(toJson).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")))
      status(response.get) must equalTo(CREATED)

      val getResource = headers(response.get).get("Location").get
      getResource must be startWith "/api/bobbyt"

      val getRec = route(implicitApp, FakeRequest(GET, getResource).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get

      status(getRec) must equalTo(OK)
      val json: Job = contentAsJson(getRec).as[Job]
      json.alert.from must equalTo(EmailAddress("from@mss.it"))
      json.alert.to must equalTo(EmailAddress("from@mss.it"))

      //look up job by token
      val allJob = route(implicitApp,FakeRequest(GET, "/api/bobbyt/job/all").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get
      status(allJob) must equalTo(OK)
      val jobs: Seq[Job] = contentAsJson(allJob).as[Seq[Job]]
      jobs.size must equalTo(1)



    }

    "return 200 when updating a bobbyt job" in new Setup {

      cleanUpDBAndCreateToken

      login

      private val id = UUID.randomUUID().toString
      private val journey: Journey = Journey(true, MeansOfTransportation(Seq(TubeLine("central", "central")), Nil), TimeOfDay(8, 30), 40)
      private val job = Job("jobTitle", alert = Email("name",EmailAddress("from@mss.it"),"name",EmailAddress("from@mss.it")),
        journey= journey,accountId = "accountId")

      //create a job
      val response = route(implicitApp, FakeRequest(POST, "/api/bobbyt").withBody(Json.toJson(job)).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")))
      status(response.get) must equalTo(CREATED)

      val getResource = headers(response.get).get("Location").get
      getResource must be startWith "/api/bobbyt"
      val getRec = route(implicitApp, FakeRequest(GET, getResource).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get

      status(getRec) must equalTo(OK)
      val json: Job = contentAsJson(getRec).as[Job]
      json.title must equalTo("jobTitle")
      json.alert.from must equalTo(EmailAddress("from@mss.it"))
      json.alert.to must equalTo(EmailAddress("from@mss.it"))
      json.journey.meansOfTransportation.tubeLines.size must equalTo(1)



      //updating the same job
      val updatedJob = json.copy(title = "Updated Job", journey = journey.copy(meansOfTransportation =
        MeansOfTransportation(Seq(TubeLine("central", "central"), TubeLine("piccadilly", "piccadilly")), Nil)))
      val responseForUpdate = route(implicitApp, FakeRequest(PUT, "/api/bobbyt").withBody(Json.toJson(updatedJob)).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")))
      status(responseForUpdate.get) must equalTo(OK)

      val getRecUpdated = route(implicitApp, FakeRequest(GET, getResource).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get

      status(getRecUpdated) must equalTo(OK)
      val updatedJobGet: Job = contentAsJson(getRecUpdated).as[Job]
      updatedJobGet.title must equalTo("Updated Job")
      updatedJobGet.journey.meansOfTransportation.tubeLines.size must equalTo(2)



    }



    "return 201 and create account record" in new Setup() {
      cleanUpDBAndCreateToken
      login

      val account = Account(userName = "neo13",email = Some(EmailAddress("test@test.it")), psw = Some("passw"))
      private val toJson = Json.toJson(account)

      val response = route(implicitApp,FakeRequest(POST, "/api/bobbyt/account").withBody(Json.parse("""{"userName":"neo13","email":{"value":"test@test.it"},"psw":"passw","active":false, "docType":"Account"}""")))
      status(response.get) must equalTo(CREATED)

      val getResource = headers(response.get).get("Location").get
      getResource must be startWith("/api/bobbyt/account")

      val getRec = route(implicitApp,FakeRequest(GET, getResource).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get
      status(getRec) must equalTo(OK)
      val json: Account = contentAsJson(getRec).as[Account]

      json.userName must equalTo(account.userName)
      json.firstName must equalTo(account.firstName)
      json.lastName must equalTo(account.lastName)
      json.email must equalTo(account.email)

    }

    "create and validate account " in new Setup() {

      cleanUpDBAndCreateToken

      val username: String = "neo13"
      val passw: String = "passw"
      val account = Account(userName = username,firstName = Some("Rocco"),lastName = Some("Bruno"), email = Some(EmailAddress("test@test.it")),
        psw = Some(passw))
      val response = route(implicitApp,FakeRequest(POST, "/api/bobbyt/account").withBody(Json.toJson(account)))
      status(response.get) must equalTo(CREATED)

      val getResource = headers(response.get).get("Location").get
      getResource must be startWith("/api/bobbyt/account")


      val ttoken = headers(response.get).get(AUTHORIZATION).get
      val getRecUpdated = route(implicitApp,FakeRequest(GET, getResource).withHeaders((HeaderNames.AUTHORIZATION, ttoken))).get
      status(getRecUpdated) must equalTo(OK)
      val jsonUpdated: Account = contentAsJson(getRecUpdated).as[Account]

      jsonUpdated.active must equalTo(true)

      //lookup account by token
      val getRecByToken = route(implicitApp,FakeRequest(GET, "/api/bobbyt/account/load").withHeaders((HeaderNames.AUTHORIZATION,
        ttoken))).get
      status(getRecByToken) must equalTo(OK)


    }

    "login via token  and submit profile" in new Setup {

      cleanUpDBAndCreateToken
      val resp = route(implicitApp, FakeRequest(POST, "/api/bobbyt/login-token").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")).withJsonBody(Json.parse("{}"))).get
      status(resp) must equalTo(CREATED)

      val tokens = Await.result(bobbytRepos.findAllToken(),10 seconds)

      tokens.size must equalTo(1)
      tokens(0).token must equalTo(token)

      val account = Account(userName = "neo13",email = Some(EmailAddress("test@test.it")), psw = Some("passw"))
      private val toJson = Json.toJson(account)

      val responseProfile = route(implicitApp,FakeRequest(POST, "/api/bobbyt/profile").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")).withJsonBody(toJson)).get
      status(responseProfile) must equalTo(CREATED)

      val getResource = headers(responseProfile).get("Location").get
      getResource must be startWith("/api/bobbyt/account")

      val getRec = route(implicitApp,FakeRequest(GET, getResource).withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get
      status(getRec) must equalTo(OK)
      val json: Account = contentAsJson(getRec).as[Account]

      json.userName must equalTo(account.userName)
      json.firstName must equalTo(account.firstName)
      json.lastName must equalTo(account.lastName)
      json.email must equalTo(account.email)

    }


    "logout a valid account" in new Setup {

      cleanUpDBAndCreateToken
      val resp = route(implicitApp, FakeRequest(POST, "/api/bobbyt/login-token").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")).withJsonBody(Json.parse("{}"))).get
      status(resp) must equalTo(CREATED)

      val response = route(implicitApp,FakeRequest(POST, "/api/bobbyt/logout").withBody("").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token"))).get
      status(response) must equalTo(OK)

      val tokens = Await.result(bobbytRepos.findAllToken(),10 seconds)
      tokens.size must equalTo(0)

      val account = Account(userName = "neo13",email = Some(EmailAddress("test@test.it")), psw = Some("passw"))
      private val toJson = Json.toJson(account)

      val responseProfile = route(implicitApp,FakeRequest(POST, "/api/bobbyt/profile").withHeaders((HeaderNames.AUTHORIZATION,
        s"Bearer $token")).withJsonBody(toJson)).get
      status(responseProfile) must equalTo(UNAUTHORIZED)

    }





  }

}
