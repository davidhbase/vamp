package io.vamp.workflow_driver

import akka.actor.{ ActorRef, ActorSystem }
import io.vamp.common.akka.IoC
import io.vamp.container_driver.marathon.MarathonDriverActor

import scala.concurrent.Future

class MarathonWorkflowDriver(implicit actorSystem: ActorSystem) extends DaemonWorkflowDriver {

  protected val namePrefixConfig = "marathon.name-prefix"

  override def info: Future[Map[_, _]] = Future.successful(Map("marathon" -> Map("url" -> MarathonDriverActor.marathonUrl)))

  override protected def driverActor: ActorRef = IoC.actorFor[MarathonDriverActor]
}
