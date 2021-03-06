/*
 * Copyright (c) 2018 https://www.reactivedesignpatterns.com/
 *
 * Copyright (c) 2018 https://rdp.reactiveplatform.xyz/
 *
 */

package chapter13

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.singleton.{
  ClusterSingletonManager,
  ClusterSingletonManagerSettings,
  ClusterSingletonProxy,
  ClusterSingletonProxySettings
}
import com.typesafe.config.{ Config, ConfigFactory }
import play.api.libs.json._

import scala.collection.immutable.TreeMap
import scala.collection.{ immutable, mutable }
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.Random

object ActivePassive {

  import Persistence._
  import ReplicationProtocol._

  private final case class Replicate(seq: Int, key: String, value: JsValue, replyTo: ActorRef)

  private final case class Replicated(seq: Int)

  private case object Tick

  private final case class TakeOver(replyTo: ActorRef)

  private final case class InitialState(map: Map[String, JsValue], seq: Int)

  object Snip13_1 {

    // #snip_13-1
    class Active(localReplica: ActorRef, replicationFactor: Int, maxQueueSize: Int)
        extends Actor
        with Stash
        with ActorLogging {

      private var theStore: Map[String, JsValue] = _
      private var seqNr: Iterator[Int] = _

      log.info("taking over from local replica")
      localReplica ! TakeOver(self)

      def receive: Receive = {
        case InitialState(m, s) =>
          log.info("took over at sequence {}", s)
          theStore = m
          seqNr = Iterator.from(s)
          context.become(running)
          unstashAll()
        case _ => stash()
      }

      val running: Receive = ??? //...
    }

    // #snip_13-1
  }

  object Snip13_2 {

    // #snip_13-2
    class Active(localReplica: ActorRef, replicationFactor: Int, maxQueueSize: Int)
        extends Actor
        with Stash
        with ActorLogging {
      // ...
      private val MaxOutstanding = maxQueueSize / 2

      private var theStore: Map[String, JsValue] = _
      private var seqNr: Iterator[Int] = _
      private val toReplicate = mutable.Queue.empty[Replicate]
      private var replicating = TreeMap.empty[Int, (Replicate, Int)]

      private var rejected = 0

      val timer: Cancellable =
        context.system.scheduler.schedule(1.second, 1.second, self, Tick)(context.dispatcher)

      override def postStop(): Unit = timer.cancel()

      log.info("taking over from local replica")
      localReplica ! TakeOver(self)

      def receive: Receive = {
        case InitialState(m, s) =>
          log.info("took over at sequence {}", s)
          theStore = m
          seqNr = Iterator.from(s)
          context.become(running)
          unstashAll()
        case _ => stash()
      }

      val running: Receive = {
        case p @ Put(key, value, replyTo) =>
          if (toReplicate.size < MaxOutstanding) {
            toReplicate.enqueue(Replicate(seqNr.next, key, value, replyTo))
            replicate()
          } else {
            rejected += 1
            replyTo ! PutRejected(key, value)
          }
        case Get(key, replyTo) =>
          replyTo ! GetResult(key, theStore.get(key))
        case Tick =>
          replicating.valuesIterator.foreach {
            case (replicate, _) => disseminate(replicate)
          }
          if (rejected > 0) {
            log.info("rejected {} PUT requests", rejected)
            rejected = 0
          }
        case Replicated(confirm) =>
          replicating.get(confirm) match {
            case None => // already removed
            case Some((rep, 1)) =>
              replicating -= confirm
              theStore += rep.key -> rep.value
              rep.replyTo ! PutConfirmed(rep.key, rep.value)
            case Some((rep, n)) =>
              replicating += confirm -> (rep, n - 1)
          }
          replicate()
      }

      private def replicate(): Unit =
        if (replicating.size < MaxOutstanding && toReplicate.nonEmpty) {
          val r = toReplicate.dequeue()
          replicating += r.seq -> (r, replicationFactor)
          disseminate(r)
        }

      private def disseminate(r: Replicate): Unit = {
        val req = r.copy(replyTo = self)
        val members = Cluster(context.system).state.members
        members.foreach(m => replicaOn(m.address) ! req)
      }

      private def replicaOn(addr: Address): ActorSelection =
        context.actorSelection(localReplica.path.toStringWithAddress(addr))
    }
    // #snip_13-2
  }

  private final case class GetSingle(seq: Int, replyTo: ActorRef)

  private final case class GetFull(replyTo: ActorRef)

  private case object DoConsolidate

  object Snip13_4 {

    // #snip_13-4
    class Passive(askAroundCount: Int, askAroundInterval: FiniteDuration, maxLag: Int) extends Actor with ActorLogging {

      private val applied = mutable.Queue.empty[Replicate]

      private val name: String =
        Cluster(context.system).selfAddress.toString.replaceAll("[:/]", "_")

      def receive: Receive = readPersisted(name) match {
        case Database(s, kv) =>
          log.info("started at sequence {}", s)
          upToDate(kv, s + 1)
      }

      def upToDate(theStore: Map[String, JsValue], expectedSeq: Int): Receive = {
        case TakeOver(active) =>
          log.info("active replica starting at sequence {}", expectedSeq)
          active ! InitialState(theStore, expectedSeq)
        case Replicate(s, _, _, replyTo) if s - expectedSeq < 0 =>
          replyTo ! Replicated(s)
        case r: Replicate if r.seq == expectedSeq =>
          val nextStore = theStore + (r.key -> r.value)
          persist(name, expectedSeq, nextStore)
          r.replyTo ! Replicated(r.seq)
          applied.enqueue(r)
          context.become(upToDate(nextStore, expectedSeq + 1))
        case r: Replicate =>
          if (r.seq - expectedSeq > maxLag)
            fallBehind(expectedSeq, TreeMap(r.seq -> r))
          else
            missingSomeUpdates(theStore, expectedSeq, Set.empty, TreeMap(r.seq -> r))
        case GetSingle(s, replyTo) =>
          log.info("GetSingle from {}", replyTo)
          if (applied.nonEmpty && applied.head.seq <= s && applied.last.seq >= s)
            replyTo ! applied.find(_.seq == s).get
          else if (s < expectedSeq) replyTo ! InitialState(theStore, expectedSeq)
        case GetFull(replyTo) =>
          log.info("sending full info to {}", replyTo)
          replyTo ! InitialState(theStore, expectedSeq)
      }

      def fallBehind(expectedSeq: Int, _waiting: TreeMap[Int, Replicate]): Unit = ???

      def missingSomeUpdates(
          theStore: Map[String, JsValue],
          expectedSeq: Int,
          prevOutstanding: Set[Int],
          waiting: TreeMap[Int, Replicate]): Unit = ???
    }

    // #snip_13-4
  }

  object Snip13_5 {

    // #snip_13-5
    class Passive(askAroundCount: Int, askAroundInterval: FiniteDuration, maxLag: Int) extends Actor with ActorLogging {

      private val applied = mutable.Queue.empty[Replicate]
      private var awaitingInitialState = Option.empty[ActorRef]

      private val name: String =
        Cluster(context.system).selfAddress.toString.replaceAll("[:/]", "_")
      private val cluster = Cluster(context.system)
      private val random = new Random

      private var tickTask = Option.empty[Cancellable]

      def scheduleTick(): Unit = {
        tickTask.foreach(_.cancel())
        tickTask = Some(
          context.system.scheduler.scheduleOnce(askAroundInterval, self, DoConsolidate)(context.dispatcher))
      }

      def receive: Receive = readPersisted(name) match {
        case Database(s, kv) =>
          log.info("started at sequence {}", s)
          upToDate(kv, s + 1)
      }

      def caughtUp(theStore: Map[String, JsValue], expectedSeq: Int): Unit = {
        awaitingInitialState.foreach(_ ! InitialState(theStore, expectedSeq))
        awaitingInitialState = None
        context.become(upToDate(theStore, expectedSeq))
      }

      def upToDate(theStore: Map[String, JsValue], expectedSeq: Int): Receive = {
        // Cases shown previously elided
        case TakeOver(active)                                   => ??? //...
        case Replicate(s, _, _, replyTo) if s - expectedSeq < 0 => ??? //...
        case r: Replicate if r.seq == expectedSeq               => ??? //...
        case r: Replicate                                       => ??? //...
        case GetSingle(s, replyTo)                              => ??? //...
        case GetFull(replyTo) =>
          log.info("sending full info to {}", replyTo)
          replyTo ! InitialState(theStore, expectedSeq)
      }

      def fallBehind(expectedSeq: Int, _waiting: TreeMap[Int, Replicate]): Unit = {
        askAroundFullState()
        scheduleTick()
        var waiting = _waiting
        context.become {
          case Replicate(s, _, _, replyTo) if s < expectedSeq =>
            replyTo ! Replicated(s)
          case r: Replicate =>
            waiting += (r.seq -> r)
          case TakeOver(active) =>
            log.info("delaying active replica takeOver, at seq {} while highest is {}", expectedSeq, waiting.lastKey)
            awaitingInitialState = Some(active)
          case InitialState(m, s) if s > expectedSeq =>
            log.info("received newer state at sequence {} (was at {})", s, expectedSeq)
            persist(name, s, m)
            waiting.to(s).valuesIterator.foreach(r => r.replyTo ! Replicated(r.seq))
            val nextWaiting = waiting.from(expectedSeq)
            consolidate(m, s + 1, Set.empty, nextWaiting)
          case DoConsolidate =>
            askAroundFullState()
            scheduleTick()
        }
      }

      private def consolidate(
          theStore: Map[String, JsValue],
          expectedSeq: Int,
          askedFor: Set[Int],
          waiting: TreeMap[Int, Replicate]): Unit = ??? //...

      private def getMembers(n: Int): Seq[Address] = {
        // using .iterator to avoid one intermediate collection to be created
        random.shuffle(cluster.state.members.iterator.map(_.address).toSeq).take(n)
      }

      private def askAroundFullState(): Unit = {
        log.info("asking for full data")
        getMembers(1).foreach(addr => replicaOn(addr) ! GetFull(self))
      }

      private def replicaOn(addr: Address): ActorSelection =
        context.actorSelection(self.path.toStringWithAddress(addr))
    }

    // #snip_13-5
  }

  object Snip13_6 {

    class Passive(askAroundCount: Int, askAroundInterval: FiniteDuration, maxLag: Int) extends Actor with ActorLogging {

      private val applied = mutable.Queue.empty[Replicate]
      private var awaitingInitialState = Option.empty[ActorRef]

      private val name = Cluster(context.system).selfAddress.toString.replaceAll("[:/]", "_")
      private val cluster = Cluster(context.system)
      private val random = new Random

      private var tickTask = Option.empty[Cancellable]

      def scheduleTick(): Unit = {
        tickTask.foreach(_.cancel())
        tickTask = Some(
          context.system.scheduler.scheduleOnce(askAroundInterval, self, DoConsolidate)(context.dispatcher))
      }

      def receive: Receive = ??? //...

      // #snip_13-6
      private val matches = (p: (Int, Int)) => p._1 == p._2

      private def consolidate(
          theStore: Map[String, JsValue],
          expectedSeq: Int,
          askedFor: Set[Int],
          waiting: TreeMap[Int, Replicate]): Unit = {

        // calculate applicable prefix length
        val prefix = waiting.keysIterator.zip(Iterator.from(expectedSeq)).takeWhile(matches).size

        val nextStore = waiting.valuesIterator.take(prefix).foldLeft(theStore) { (store, replicate) =>
          persist(name, replicate.seq, theStore)
          replicate.replyTo ! Replicated(replicate.seq)
          applied.enqueue(replicate)
          store + (replicate.key -> replicate.value)
        }
        val nextWaiting = waiting.drop(prefix)
        val nextExpectedSeq = expectedSeq + prefix

        // cap the size of the applied buffer
        applied.drop(Math.max(0, applied.size - maxLag))

        if (nextWaiting.nonEmpty) {
          // check if we fell behind by too much
          if (nextWaiting.lastKey - nextExpectedSeq > maxLag)
            fallBehind(nextExpectedSeq, nextWaiting)
          else missingSomeUpdates(nextStore, nextExpectedSeq, askedFor, nextWaiting)
        } else caughtUp(nextStore, nextExpectedSeq)
      }

      // #snip_13-6

      def caughtUp(theStore: Map[String, JsValue], expectedSeq: Int): Unit = ???

      def fallBehind(expectedSeq: Int, _waiting: TreeMap[Int, Replicate]): Unit = ???

      def missingSomeUpdates(
          theStore: Map[String, JsValue],
          expectedSeq: Int,
          prevOutstanding: Set[Int],
          waiting: TreeMap[Int, Replicate]): Unit = ???

    }

  }

  object Snip13_7 {

    // #snip_13-7
    class Passive(askAroundCount: Int, askAroundInterval: FiniteDuration, maxLag: Int) extends Actor with ActorLogging {

      private val applied = mutable.Queue.empty[Replicate]
      private var awaitingInitialState = Option.empty[ActorRef]

      // ... Initialization elided
      def receive: Receive = ??? //...

      def upToDate(theStore: Map[String, JsValue], expectedSeq: Int): Receive = {
        case TakeOver(active)                                   => ??? //...
        case Replicate(s, _, _, replyTo) if s - expectedSeq < 0 => ??? //...
        case r: Replicate if r.seq == expectedSeq               => ??? //...
        case r: Replicate                                       => ??? //...
        case GetFull(replyTo)                                   => ??? //...
        case GetSingle(s, replyTo) =>
          log.info("GetSingle from {}", replyTo)
          if (applied.nonEmpty &&
              applied.head.seq <= s && applied.last.seq >= s) {
            replyTo ! applied.find(_.seq == s).get
          } else if (s < expectedSeq) {
            replyTo ! InitialState(theStore, expectedSeq)
          }
      }

      def missingSomeUpdates(
          theStore: Map[String, JsValue],
          expectedSeq: Int,
          prevOutstanding: Set[Int],
          waiting: TreeMap[Int, Replicate]): Unit = {

        val askFor = (expectedSeq to waiting.lastKey).iterator
          .filterNot(seq =>
            waiting.contains(seq) ||
            prevOutstanding.contains(seq))
          .toList

        askFor.foreach(askAround)

        if (prevOutstanding.isEmpty) {
          scheduleTick()
        }
        val outstanding = prevOutstanding ++ askFor

        context.become {
          case Replicate(s, _, _, replyTo) if s < expectedSeq =>
            replyTo ! Replicated(s)
          case r: Replicate =>
            consolidate(theStore, expectedSeq, outstanding - r.seq, waiting + (r.seq -> r))
          case TakeOver(active) =>
            log.info("delaying active replica takeOver, at seq {} while highest is {}", expectedSeq, waiting.lastKey)
            awaitingInitialState = Some(active)
          case GetSingle(s, replyTo) =>
            log.info("GetSingle from {}", replyTo)
            if (applied.nonEmpty &&
                applied.head.seq <= s &&
                applied.last.seq >= s) {
              replyTo ! applied.find(_.seq == s).get
            } else if (s < expectedSeq) {
              replyTo ! InitialState(theStore, expectedSeq)
            }
          case GetFull(replyTo) =>
            log.info("sending full info to {}", replyTo)
            replyTo ! InitialState(theStore, expectedSeq)
          case DoConsolidate =>
            outstanding.foreach(askAround)
            scheduleTick()
        }
      }

      private def askAround(seq: Int): Unit = {
        log.info("asking around for sequence number {}", seq)
        getMembers(askAroundCount).foreach(addr => replicaOn(addr) ! GetSingle(seq, self))
      }

      // ... Other helpers elided
      private def consolidate(
          theStore: Map[String, JsValue],
          expectedSeq: Int,
          askedFor: Set[Int],
          waiting: TreeMap[Int, Replicate]): Unit = ???

      private def getMembers(n: Int): Seq[Address] = ???

      private def replicaOn(addr: Address): ActorSelection = ???

      def scheduleTick(): Unit = ???

    }

    // #snip_13-7
  }

  class Passive(askAroundCount: Int, askAroundInterval: FiniteDuration, maxLag: Int) extends Actor with ActorLogging {

    private val applied = mutable.Queue.empty[Replicate]
    private var awaitingInitialState = Option.empty[ActorRef]

    private val name = Cluster(context.system).selfAddress.toString.replaceAll("[:/]", "_")
    private val cluster = Cluster(context.system)
    private val random = new Random

    private var tickTask = Option.empty[Cancellable]

    def scheduleTick(): Unit = {
      tickTask.foreach(_.cancel())
      tickTask = Some(context.system.scheduler.scheduleOnce(askAroundInterval, self, DoConsolidate)(context.dispatcher))
    }

    def receive: Receive = readPersisted(name) match {
      case Database(s, kv) =>
        log.info("started at sequence {}", s)
        upToDate(kv, s + 1)
    }

    override def postStop(): Unit = {
      log.info("stopped")
    }

    def caughtUp(theStore: Map[String, JsValue], expectedSeq: Int): Unit = {
      awaitingInitialState.foreach(_ ! InitialState(theStore, expectedSeq))
      awaitingInitialState = None
      context.become(upToDate(theStore, expectedSeq))
    }

    def upToDate(theStore: Map[String, JsValue], expectedSeq: Int): Receive = {
      case TakeOver(active) =>
        log.info("active replica starting at sequence {}", expectedSeq)
        active ! InitialState(theStore, expectedSeq)
      case Replicate(s, _, _, replyTo) if s - expectedSeq < 0 =>
        replyTo ! Replicated(s)
      case r: Replicate if r.seq == expectedSeq =>
        val nextStore = theStore + (r.key -> r.value)
        persist(name, expectedSeq, nextStore)
        r.replyTo ! Replicated(r.seq)
        applied.enqueue(r)
        context.become(upToDate(nextStore, expectedSeq + 1))
      case r: Replicate =>
        if (r.seq - expectedSeq > maxLag)
          fallBehind(expectedSeq, TreeMap(r.seq -> r))
        else
          missingSomeUpdates(theStore, expectedSeq, Set.empty, TreeMap(r.seq -> r))
      case GetSingle(s, replyTo) =>
        log.info("GetSingle from {}", replyTo)
        if (applied.nonEmpty && applied.head.seq <= s && applied.last.seq >= s)
          replyTo ! applied.find(_.seq == s).get
        else if (s < expectedSeq) replyTo ! InitialState(theStore, expectedSeq)
      case GetFull(replyTo) =>
        log.info("sending full info to {}", replyTo)
        replyTo ! InitialState(theStore, expectedSeq)
    }

    def missingSomeUpdates(
        theStore: Map[String, JsValue],
        expectedSeq: Int,
        prevOutstanding: Set[Int],
        waiting: TreeMap[Int, Replicate]): Unit = {

      val askFor = (expectedSeq to waiting.lastKey).iterator
        .filterNot(seq =>
          waiting.contains(seq) ||
          prevOutstanding.contains(seq))
        .toList
      askFor.foreach(askAround)
      if (prevOutstanding.isEmpty) scheduleTick()
      val outstanding = prevOutstanding ++ askFor
      context.become {
        case Replicate(s, _, _, replyTo) if s < expectedSeq =>
          replyTo ! Replicated(s)
        case r: Replicate =>
          consolidate(theStore, expectedSeq, outstanding - r.seq, waiting + (r.seq -> r))
        case TakeOver(active) =>
          log.info("delaying active replica takeOver, at seq {} while highest is {}", expectedSeq, waiting.lastKey)
          awaitingInitialState = Some(active)
        case GetSingle(s, replyTo) =>
          log.info("GetSingle from {}", replyTo)
          if (applied.nonEmpty && applied.head.seq <= s && applied.last.seq >= s)
            replyTo ! applied.find(_.seq == s).get
          else if (s < expectedSeq) replyTo ! InitialState(theStore, expectedSeq)
        case GetFull(replyTo) =>
          log.info("sending full info to {}", replyTo)
          replyTo ! InitialState(theStore, expectedSeq)
        case DoConsolidate =>
          outstanding.foreach(askAround)
          scheduleTick()
      }
    }

    def fallBehind(expectedSeq: Int, _waiting: TreeMap[Int, Replicate]): Unit = {
      askAroundFullState()
      scheduleTick()
      var waiting = _waiting
      context.become {
        case Replicate(s, _, _, replyTo) if s < expectedSeq =>
          replyTo ! Replicated(s)
        case r: Replicate =>
          waiting += (r.seq -> r)
        case TakeOver(active) =>
          log.info("delaying active replica takeOver, at seq {} while highest is {}", expectedSeq, waiting.lastKey)
          awaitingInitialState = Some(active)
        case InitialState(m, s) if s > expectedSeq =>
          log.info("received newer state at sequence {} (was at {})", s, expectedSeq)
          persist(name, s, m)
          waiting.to(s).valuesIterator.foreach(r => r.replyTo ! Replicated(r.seq))
          val nextWaiting = waiting.from(s + 1)
          consolidate(m, s + 1, Set.empty, nextWaiting)
        case DoConsolidate =>
          askAroundFullState()
          scheduleTick()
      }
    }

    private val matches = (p: (Int, Int)) => p._1 == p._2

    private def consolidate(
        theStore: Map[String, JsValue],
        expectedSeq: Int,
        askedFor: Set[Int],
        waiting: TreeMap[Int, Replicate]): Unit = {
      // calculate applicable prefix length
      val prefix = waiting.keysIterator.zip(Iterator.from(expectedSeq)).takeWhile(matches).size

      val nextStore = waiting.valuesIterator.take(prefix).foldLeft(theStore) { (store, replicate) =>
        persist(name, replicate.seq, theStore)
        replicate.replyTo ! Replicated(replicate.seq)
        applied.enqueue(replicate)
        store + (replicate.key -> replicate.value)
      }
      val nextWaiting = waiting.drop(prefix)
      val nextExpectedSeq = expectedSeq + prefix

      // cap the size of the applied buffer
      applied.drop(Math.max(0, applied.size - maxLag))

      if (nextWaiting.nonEmpty) {
        // check if we fell behind by too much
        if (nextWaiting.lastKey - nextExpectedSeq > maxLag)
          fallBehind(nextExpectedSeq, nextWaiting)
        else missingSomeUpdates(nextStore, nextExpectedSeq, askedFor, nextWaiting)
      } else caughtUp(nextStore, nextExpectedSeq)
    }

    private def getMembers(n: Int): Seq[Address] = {
      // using .iterator to avoid one intermediate collection to be created
      random.shuffle(cluster.state.members.iterator.map(_.address).toSeq).take(n)
    }

    private def askAround(seq: Int): Unit = {
      log.info("asking around for sequence number {}", seq)
      getMembers(askAroundCount).foreach(addr => replicaOn(addr) ! GetSingle(seq, self))
    }

    private def askAroundFullState(): Unit = {
      log.info("asking for full data")
      getMembers(1).foreach(addr => replicaOn(addr) ! GetFull(self))
    }

    private def replicaOn(addr: Address): ActorSelection =
      context.actorSelection(self.path.toStringWithAddress(addr))
  }

  val commonConfig: Config = ConfigFactory.parseString("""
    akka.actor.provider = akka.cluster.ClusterActorRefProvider
    akka.remote.netty.tcp {
      host = "127.0.0.1"
      port = 0
    }
    akka.cluster {
      gossip-interval = 100ms
      failure-detector {
        heartbeat-interval = 100ms
        acceptable-heartbeat-pause = 500ms
      }
    }
    """)

  def roleConfig(name: String, port: Option[Int]): Config = {
    val roles = ConfigFactory.parseString(s"""akka.cluster.roles = ["$name"]""")
    port match {
      case None => roles
      case Some(p) =>
        ConfigFactory.parseString(s"""akka.remote.netty.tcp.port = $p""").withFallback(roles)
    }
  }

  def start(port: Option[Int]): ActorSystem = {
    val system = ActorSystem("ActivePassive", roleConfig("backend", port).withFallback(commonConfig))
    val localReplica = system.actorOf(Props(new Passive(3, 3.seconds, 100)), "passive")
    val settings = ClusterSingletonManagerSettings(system)
      .withSingletonName("active")
      .withRole("backend")
      .withHandOverRetryInterval(150.millis)
    val managerProps =
      ClusterSingletonManager.props(Props(new Snip13_2.Active(localReplica, 2, 120)), PoisonPill, settings)
    val manager = system.actorOf(managerProps, "activeManager")
    system
  }

  def main(args: Array[String]): Unit = {
    val systems = Array.fill(5)(start(None))
    val seedNode = Cluster(systems(0)).selfAddress
    systems.foreach(Cluster(_).join(seedNode))

    val sys = ActorSystem("ActivePassive", ConfigFactory.parseString("akka.loglevel=INFO").withFallback(commonConfig))
    Cluster(sys).join(seedNode)

    awaitMembers(sys, systems.length + 1)

    val proxySettings = ClusterSingletonProxySettings(sys).withRole("backend").withSingletonName("active")
    val proxy = sys.actorOf(ClusterSingletonProxy.props("/user/activeManager", proxySettings), "proxy")

    val useStorage = sys.actorOf(Props(new UseStorage(proxy)), "useStorage")
    useStorage ! Run(0)

    sys.actorOf(Props(new Actor {
      def receive: Receive = {
        case Run =>
          StdIn.readLine()
          useStorage ! Stop
      }
    })) ! Run

    Thread.sleep(10000)

    val rnd = new Random
    while (!terminate) {
      Thread.sleep(5000)
      val sysIdx = rnd.nextInt(systems.length)
      val oldSys = systems(sysIdx)
      val port = Cluster(oldSys).selfAddress.port
      Await.ready(oldSys.terminate(), Duration.Inf)
      val newSys = start(port)
      val seed = Cluster(if (sysIdx == 0) systems(1) else systems(0)).selfAddress
      Cluster(newSys).join(seed)
      systems(sysIdx) = newSys
      awaitMembers(sys, systems.length + 1)
    }

    Thread.sleep(3000)

    sys.terminate()
    systems.foreach(_.terminate())
  }

  private def awaitMembers(sys: ActorSystem, count: Int): Unit = {
    while (Cluster(sys).state.members.size < count) {
      Thread.sleep(500)
      print('.')
      Console.flush()
    }
    println("cluster started")
  }

  private final case class Run(round: Int)

  private case object Stop

  @volatile private var terminate = false

  private class UseStorage(db: ActorRef) extends Actor with ActorLogging {
    private val N = 200
    private var theStore = Map.empty[String, JsValue]
    private val keys: immutable.IndexedSeq[String] = (1 to N).map(i => f"$i%03d")
    private var outstanding = Set.empty[String]
    private val rnd = new Random
    private var lastOutstandingCount = 0

    def receive: Receive = {
      case Run(0) =>
        db ! Get("initial", self)
      case GetResult("initial", _) =>
        self ! Run(1)
      case Run(round) =>
        if (round % 100 == 0) log.info("round {}", round)
        val nowOutstanding = outstanding.size
        if (nowOutstanding != lastOutstandingCount) {
          lastOutstandingCount = nowOutstanding
          log.info("{} outstanding", nowOutstanding)
        }
        for (k <- keys) {
          db ! Get(k, self)
          if (!outstanding.contains(k) && rnd.nextBoolean()) {
            db ! Put(k, JsNumber(round), self)
            outstanding += k
          }
        }
        context.system.scheduler.scheduleOnce(100.millis, self, Run(round + 1))(context.dispatcher)
      case GetResult(key, value) =>
        if (outstanding.contains(key)) {
          outstanding -= key
          value.foreach(theStore += key -> _)
        } else if (value != theStore.get(key)) {
          log.warning("returned wrong value for key {}: {} (expected {})", key, value, theStore.get(key))
          context.stop(self)
        }
      case PutConfirmed(key, value) =>
        outstanding -= key
        theStore += key -> value
      case PutRejected(key, value) =>
        outstanding -= key
      case Stop => context.stop(self)
    }

    override def postStop(): Unit = terminate = true
  }

  /*
 * Problems:
 * - when after TakeOver the localReplica gets a full map that has newer info, we potentially
 *   accepted conflicting writes
 * - after TakeOver we should probably do a reconciliation phase that brings all known replicas
 *   up to speed
 * - but if a replica with newer data joins later then we’ll need to wipe its contents and roll
 *   it back because a different universe has won
 * - in general there is the question of when to wipe a local copy that gets kicked out: we
 *   want that for cases where it was in a losing partition but NOT when the whole cluster goes
 *   down
 * - I guess that this can only be reconciled later when joining things back together.
 */

}
