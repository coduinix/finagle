package com.twitter.finagle.builder

import com.twitter.finagle._
import com.twitter.finagle.integration.IntegrationBase
import com.twitter.finagle.service.{RetryPolicy, FailureAccrualFactory}
import com.twitter.finagle.stats.{NullStatsReceiver, InMemoryStatsReceiver}
import com.twitter.util._
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import org.junit.runner.RunWith
import org.mockito.Mockito.{verify, when}
import org.mockito.Matchers
import org.mockito.Matchers._
import org.scalatest.FunSuite
import org.scalatest.mock.MockitoSugar
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

@RunWith(classOf[JUnitRunner])
class ClientBuilderTest extends FunSuite
  with Eventually
  with IntegrationPatience
  with MockitoSugar
  with IntegrationBase {

  trait ClientBuilderHelper {
    val preparedFactory = mock[ServiceFactory[String, String]]
    val preparedServicePromise = new Promise[Service[String, String]]
    when(preparedFactory.status) thenReturn Status.Open
    when(preparedFactory()) thenReturn preparedServicePromise
    when(preparedFactory.close(any[Time])) thenReturn Future.Done
    when(preparedFactory.map(Matchers.any())) thenReturn
      preparedFactory.asInstanceOf[ServiceFactory[Any, Nothing]]

    val m = new MockChannel
    when(m.codec.prepareConnFactory(any[ServiceFactory[String, String]])) thenReturn preparedFactory
  }

  test("ClientBuilder should invoke prepareConnFactory on connection") {
    new ClientBuilderHelper {
      val client = m.build()
      val requestFuture = client("123")

      verify(m.codec).prepareConnFactory(any[ServiceFactory[String, String]])
      verify(preparedFactory)()

      assert(!requestFuture.isDefined)
      val service = mock[Service[String, String]]
      when(service("123")) thenReturn Future.value("321")
      when(service.close(any[Time])) thenReturn Future.Done
      preparedServicePromise() = Return(service)

      verify(service)("123")
      assert(requestFuture.poll === Some(Return("321")))
    }
  }

  test("ClientBuilder should close properly") {
    new ClientBuilderHelper {
      val svc = ClientBuilder().hostConnectionLimit(1).codec(m.codec).hosts("").build()
      val f = svc.close()
      eventually {
        f.isDefined
      }
    }
  }

  test("ClientBuilder should collect stats on 'tries' for retrypolicy") {
    new ClientBuilderHelper {
      val inMemory = new InMemoryStatsReceiver
      val builder = ClientBuilder()
        .name("test")
        .hostConnectionLimit(1)
        .codec(m.codec)
        .daemon(true) // don't create an exit guard
        .hosts(Seq(m.clientAddress))
        .retries(2) // retries === total attempts :(
        .reportTo(inMemory)
      val client = builder.build()

      val FailureAccrualFactory.Param.Configured(numFailures, _) =
        builder.params(FailureAccrualFactory.Param.param)

      val service = mock[Service[String, String]]
      when(service("123")) thenReturn Future.exception(WriteException(new Exception()))
      when(service.close(any[Time])) thenReturn Future.Done
      preparedServicePromise() = Return(service)

      val f = client("123")

      eventually { assert(f.isDefined) }
      assert(inMemory.counters(Seq("test", "tries", "requests")) === 1)
      assert(
        // one request which requeues until failure accrual limit + one retry
        inMemory.counters(Seq("test", "requests")) === 1 + numFailures
      )
    }
  }

  test("ClientBuilder should collect stats on 'tries' with no retrypolicy") {
    new ClientBuilderHelper {
      val inMemory = new InMemoryStatsReceiver
      val builder = ClientBuilder()
        .name("test")
        .hostConnectionLimit(1)
        .codec(m.codec)
        .daemon(true) // don't create an exit guard
        .hosts(Seq(m.clientAddress))
        .reportTo(inMemory)

      val client = builder.build()

      val FailureAccrualFactory.Param.Configured(numFailures, _) =
        builder.params(FailureAccrualFactory.Param.param)

      val service = mock[Service[String, String]]
      when(service("123")) thenReturn Future.exception(WriteException(new Exception()))
      when(service.close(any[Time])) thenReturn Future.Done
      preparedServicePromise() = Return(service)

      val f = client("123")

      assert(f.isDefined)
      assert(inMemory.counters(Seq("test", "tries", "requests")) === 1)

      // failure accrual marks the only node in the balancer as Busy which in turn caps requeues
      assert(inMemory.counters(Seq("test", "requests")) === numFailures)
    }
  }

  test("ClientBuilder with stack should collect stats on 'tries' for retrypolicy") {
    new ClientBuilderHelper {
      val inMemory = new InMemoryStatsReceiver
      val builder = ClientBuilder()
        .name("test")
        .hostConnectionLimit(1)
        .stack(m.client)
        .daemon(true) // don't create an exit guard
        .hosts(Seq(m.clientAddress))
        .retries(2) // retries === total attempts :(
        .reportTo(inMemory)
      val client = builder.build()

      val FailureAccrualFactory.Param.Configured(numFailures, _) =
        builder.params(FailureAccrualFactory.Param.param)
      val service = mock[Service[String, String]]
      when(service("123")) thenReturn Future.exception(WriteException(new Exception()))
      when(service.close(any[Time])) thenReturn Future.Done
      preparedServicePromise() = Return(service)

      val f = client("123")

      eventually { assert(f.isDefined) }
      assert(inMemory.counters(Seq("test", "tries", "requests")) === 1)

      // failure accrual limited requeues + one retry which is not requeued
      assert(inMemory.counters(Seq("test", "requests")) === 1 + numFailures)
    }
  }

  test("ClientBuilder with stack should collect stats on 'tries' with no retrypolicy") {
    new ClientBuilderHelper {
      val inMemory = new InMemoryStatsReceiver
      val builder = ClientBuilder()
        .name("test")
        .hostConnectionLimit(1)
        .stack(m.client)
        .daemon(true) // don't create an exit guard
        .hosts(Seq(m.clientAddress))
        .failureAccrualParams(25 -> Duration.fromSeconds(10))
        .reportTo(inMemory)

      val client = builder.build()

      val FailureAccrualFactory.Param.Configured(numFailures, _) =
        builder.params(FailureAccrualFactory.Param.param)

      val service = mock[Service[String, String]]
      when(service("123")) thenReturn Future.exception(WriteException(new Exception()))
      when(service.close(any[Time])) thenReturn Future.Done
      preparedServicePromise() = Return(service)

      val f = client("123")

      assert(f.isDefined)
      assert(inMemory.counters(Seq("test", "tries", "requests")) === 1)
      assert(inMemory.counters(Seq("test", "requests")) === numFailures)
    }
  }

  private class SpecificException extends RuntimeException

  test("Retries have locals propagated") {

    new ClientBuilderHelper {
      val specificExceptionRetry: PartialFunction[Try[Nothing], Boolean] = {
        case Throw(e: SpecificException) => true
      }
      val builder = ClientBuilder()
        .name("test")
        .hostConnectionLimit(1)
        .stack(m.client)
        .daemon(true) // don't create an exit guard
        .hosts(Seq(m.clientAddress))
        .retryPolicy(RetryPolicy.tries(2, specificExceptionRetry))
        .reportTo(NullStatsReceiver)
      val client = builder.build()

      val aLocal = new Local[Int]
      val first = new AtomicBoolean(false)
      val localOnRetry = new AtomicInteger(0)

      // 1st call fails and triggers a retry which
      // captures the value of the local
      val service = Service.mk[String, String] { str: String =>
        if (first.compareAndSet(false, true)) {
          Future.exception(new SpecificException())
        } else {
          localOnRetry.set(aLocal().getOrElse(-1))
          Future(str)
        }
      }
      preparedServicePromise() = Return(service)

      aLocal.let(999) {
        val rep = client("hi")
        assert("hi" == Await.result(rep, Duration.fromSeconds(5)))
      }
      assert(999 == localOnRetry.get)
    }
  }

}
