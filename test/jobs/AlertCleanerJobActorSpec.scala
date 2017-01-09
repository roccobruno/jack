package jobs

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit}
import model.{Email, EmailAddress, EmailAlert}
import org.joda.time.DateTime
import play.api.Configuration
import play.api.libs.ws.WSClient
import repository.{BobbytRepository, TubeRepository}
import service.{JobService, MailGunService}
import util.Testing

import scala.concurrent.duration._
import scala.concurrent.Await
import akka.pattern.ask
import akka.util.Timeout
import org.mockito.Mockito



class AlertCleanerJobActorSpec extends TestKit(ActorSystem("AlertCleanerJobActorSpec")) with Testing {


  val bobbytRepository = BobbytRepository

  override protected def beforeAll(): Unit = {
   await(bobbytRepository.deleteAllAlerts())
  }

  "an actor " should {

    "delete all the alert sent" in {

      val jbService =  new JobService {
        override val repo: BobbytRepository = bobbytRepository
        override val mailGunService: MailGunService = Mockito.mock(classOf[MailGunService])
        override val ws: WSClient = Mockito.mock(classOf[WSClient])
        override val configuration: Configuration = Mockito.mock(classOf[Configuration])
        override val tubeRepository: TubeRepository = Mockito.mock(classOf[TubeRepository])
      }

      val actor = TestActorRef(new AlertCleanerJobActor(jbService))


      await(bobbytRepository.saveAlert(
        EmailAlert(
          UUID.randomUUID().toString,
          Email("", EmailAddress("test@test.it"),"", EmailAddress("to@to.it")),
          DateTime.now.minusDays(3),
          Some(DateTime.now().minusDays(2)),
          UUID.randomUUID().toString,
          "Alert",
          sent = true)))



      await(bobbytRepository.saveAlert(
        EmailAlert(
          UUID.randomUUID().toString,
          Email("", EmailAddress("test@test.it"),"", EmailAddress("to@to.it")),
          DateTime.now.minusDays(3),
          Some(DateTime.now().minusDays(2)),
          UUID.randomUUID().toString,
          "Alert",
          sent = true)))

      await(bobbytRepository.saveAlert(
        EmailAlert(
          UUID.randomUUID().toString,
          Email("", EmailAddress("test@test.it"),"", EmailAddress("to@to.it")),
          DateTime.now,
          Some(DateTime.now),
          UUID.randomUUID().toString,
          "Alert",
          sent = true)))

      val alerts = await(bobbytRepository.findAllAlert())
      alerts.size shouldBe 3

      implicit val timeout: Timeout = Timeout(10000, TimeUnit.MILLISECONDS)
      val res = actor ! Run("test")


      val alertsAfterActor = await(bobbytRepository.findAllAlert())
      alertsAfterActor.size shouldBe 1




    }


  }


}