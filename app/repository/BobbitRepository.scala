package repository

import java.util.UUID
import java.util.concurrent.TimeUnit
import javafx.scene.control.Alert

import com.couchbase.client.java.document.json.JsonArray
import com.couchbase.client.java.query.N1qlQuery
import com.couchbase.client.java.{AsyncBucket, CouchbaseCluster}
import com.couchbase.client.protocol.views._
import model.Job
import org.joda.time.DateTime
import org.reactivecouchbase.CouchbaseExpiration.{CouchbaseExpirationTiming, CouchbaseExpirationTiming_byDuration, CouchbaseExpirationTiming_byInt}
import org.reactivecouchbase.{CouchbaseBucket, ReactiveCouchbaseDriver}
import org.reactivecouchbase.play.PlayCouchbase
import play.api.libs.json._
import org.reactivecouchbase.client.Constants
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global
import org.reactivecouchbase.play.plugins.CouchbaseN1QLPlugin._

import scala.concurrent.{Awaitable, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Try}
import model._
import org.asyncouchbase.bucket.BucketApi
import org.asyncouchbase.index.IndexApi
import org.asyncouchbase.model.OpsResult
import play.api.Logger
import play.api.libs.iteratee.Enumerator
import org.asyncouchbase.query.Expression._
import org.asyncouchbase.query.SimpleQuery
import org.asyncouchbase.util.Reflection

import scala.reflect.runtime.universe._


object BobbitRepository extends BobbitRepository {



//  val driver = ReactiveCouchbaseDriver()
//  val cluster = CouchbaseCluster.create("localhost")
//  implicit override lazy val bobbitBucket = driver.bucket("bobbit")

  val cluster = CouchbaseCluster.create()
  val bucket = new IndexApi {
    override def asyncBucket: AsyncBucket = cluster.openBucket("bobbit").async()
  }


  //by_type_token
  bucket.createPrimaryIndex(deferBuild = false) map {
    _ => Logger.info("PRIMARY INDEX CREATED")
  } recover {
    case ed: Throwable => Logger.error(s"PRIMARY INDEX NOT CREATED error= ${ed.getMessage}")
  }

  bucket.createIndex(Seq("token", "docType"), deferBuild = false) map {

    _ => Logger.info("SECONDARY INDEX CREATED")
  } recover {
    case ed: Throwable => Logger.error(s"SECONDARY INDEX CREATED error= ${ed.getMessage}")
  }




}


trait BobbitRepository {

  def cluster:CouchbaseCluster

  def bucket: IndexApi

  def deleteAllRunningJob() = deleteAll(findAllRunningJob)


  def deleteAllJobs() = {
    deleteAll(findAllJob)
  }

  def deleteAllAccount() = {
    deleteAll(findAllAccount)
  }

  def deleteAllToken() = {
    deleteAll(findAllToken)
  }

  def deleteAllAlerts() = {
    deleteAll(findAllAlert)
  }


  def deleteAll[T <: InternalId](findAll: () => Future[Seq[T]]) = {
    findAll() map {
      recs =>
        recs map (rec => deleteById(rec.getId))
    } recover {
      case _ => println("Error in deleting rows. Probably no rows were found")
    }
  }

  def activateAccount(token: Token, tokenValue: String): Future[Option[String]] = {
    for {
      acc <- findById[Account](token.accountId)
      resultSaving <- saveAccount(acc, token.accountId)
      result <- deleteById(token.getId)
    } yield result

  }

  def saveAccount(account: Option[Account], accountId: String): Future[Option[String]] = {
    account match {
      case None => Future.successful(Some(s"no account found for id=$accountId"))
      case Some(acc) => saveAccount(acc.copy(active = true))
    }
  }

  def save[T <: InternalId](entity: T)(implicit expirationTime: CouchbaseExpirationTiming = Constants.expiration, writes: Writes[T]):
  Future[Option[String]] = {
    val id = entity.getId
    bucket.upsert[T](id, entity) map {
      case o: OpsResult if o.isSuccess => Some(id)
      case o: OpsResult => println(s"error in saving entity: $entity - opResult:${o.msg}"); None
    }
  }



  def saveAlert(alert: EmailAlert): Future[Option[String]] = save[EmailAlert](alert)

  def saveJob(job: Job): Future[Option[String]] = save[Job](job)

  def saveRunningJob(job: RunningJob): Future[Option[String]] = save[RunningJob](job)

  def saveToken(token: Token): Future[Option[String]] = {
    implicit val expirationTiming = CouchbaseExpirationTiming_byDuration(Duration.create(30, TimeUnit.MINUTES))
    save[Token](token)
  }

  def saveAccount(account: Account): Future[Option[String]] = save[Account](account)


  def findRunningJobById(id: String): Future[Option[RunningJob]] = findById[RunningJob](id)

  def findJobById(id: String): Future[Option[Job]] = findById[Job](id)

  def findAccountById(id: String): Future[Option[Account]] = findById[Account](id)

  def findById[T](id: String)(implicit rds: Reads[T]): Future[Option[T]] = bucket.get[T](id)

  def findValidTokenByValue(token: String): Future[Option[Token]] = {
    val query = new SimpleQuery[Token]() SELECT "*" FROM "bobbit" WHERE ("token" === token AND "docType" === "Token")
    bucket.find[Token](query) map {
      case head:: tail => Some(head)
      case Nil => None
    }

  }

  def findAccountByUserName(userName: String): Future[List[Account]] = {

    val query = new SimpleQuery[Account]() SELECT "*" FROM "bobbit" WHERE ("userName" === userName AND "docType" === "Account")
    bucket.find[Account](query)

  }


  def deleteById(id: String): Future[Option[String]] = {

    bucket.delete(id) map {
      case o: OpsResult if o.isSuccess => Some(id)
      case o: OpsResult => println(s"error in deleting object with id: $id, opResult:${o.msg}"); None
    }
  }


  def findAllByType[T: TypeTag](docType: String)(implicit rds: Reads[T]): Future[List[T]] = {
    val query = new SimpleQuery[T]() SELECT "*" FROM "bobbit" WHERE ("docType" === docType)
    bucket.find[T](query)
  }

  def findAllRunningJob(): Future[List[RunningJob]] = {
    findAllByType[RunningJob]("RunningJob")
  }

  def findAllAlert(): Future[List[EmailAlert]] = {
    findAllByType[EmailAlert]("Alert")
  }

  def findAllAccount(): Future[List[Account]] = {
    findAllByType[Account]("Account")
  }

  def findAllToken(): Future[List[Token]] = {
    findAllByType[Token]("Token")
  }

  def findAllJob(): Future[List[Job]] = {
    findAllByType[Job]("Job")
  }

  def findAllJobByAccountId(accountId: String): Future[List[Job]] = {

    val query = new SimpleQuery[Job]() SELECT "*" FROM "bobbit" WHERE ("docType" === "Job" AND "accountId" === accountId)
    bucket.find[Job](query)
  }

  def findRunningJobByJobId(jobId: String): Future[Option[RunningJob]] = {
    val query = new SimpleQuery[RunningJob]() SELECT "*" FROM "bobbit" WHERE ("docType" === "RunningJob" AND "jobId" === jobId)
    bucket.find[RunningJob](query) map {
      case head :: tail => Some(head)
      case Nil => None
    }
  }


  def findRunningJobToExecute(): Future[Set[RunningJob]] = {
    for {
      first <- findRunningJobToExecuteByStartTime()
      second <- findRunningJobToExecuteByEndTime()
    } yield (first ++ second).toSet
  }

  def findRunningJobToExecuteByStartTime(): Future[Seq[RunningJob]] = {

    val now: DateTime = DateTime.now()
    val timeFrom = timeOfDay(now)
    val timeTO = timeOfDay(now.plusMinutes(30))

    val query = new SimpleQuery[RunningJob]() SELECT "*" FROM "bobbit" WHERE
      ("docType" === "RunningJob" AND "recurring" === true AND "alertSent" === false AND
        ( "fromTime.time" BETWEEN (timeFrom AND  timeTO)))

    bucket.find[RunningJob](query)

  }

  def findRunningJobToExecuteByEndTime(): Future[Seq[RunningJob]] = {

    val now: DateTime = DateTime.now()
    val timeFrom = timeOfDay(now)
    val timeTO = timeOfDay(now.plusMinutes(30))


    val query = new SimpleQuery[RunningJob]() SELECT "*" FROM "bobbit" WHERE
      ("docType" === "RunningJob" AND "recurring" === true AND "alertSent" === false AND
        ( "toTime.time" BETWEEN (timeFrom AND  timeTO)))

    bucket.find[RunningJob](query)

  }


  def findRunningJobToReset(): Future[Seq[RunningJob]] = {

    val now: DateTime = DateTime.now()
    val timeTO = timeOfDay(now.minusHours(1))

    val query = new SimpleQuery[RunningJob]() SELECT "*" FROM "bobbit" WHERE
      ("docType" === "RunningJob" AND "recurring" === true AND "alertSent" === true AND ("toTime.time" gt timeTO))

    bucket.find[RunningJob](query)


  }

  private def timeOfDay(tm: DateTime): Int = TimeOfDay.time(tm.hourOfDay().get(), tm.minuteOfHour().get())

}
