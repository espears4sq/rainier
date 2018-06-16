package com.stripe.rainier.core

import com.stripe.rainier.compute._
import com.stripe.rainier.sampler._

/**
  * The main probability monad used in Rainier for constructing probabilistic programs which can be sampled
  */
class RandomVariable[+T](val value: T,
                         private val densities: Set[RandomVariable.BoxedReal],
                         private val batches: Set[Batches]) {

  def flatMap[U](fn: T => RandomVariable[U]): RandomVariable[U] = {
    val rv = fn(value)
    require(
      batches.isEmpty || rv.batches.isEmpty ||
        batches.head.numBatches == rv.batches.head.numBatches)
    new RandomVariable(rv.value,
                       densities ++ rv.densities,
                       batches ++ rv.batches)
  }

  def map[U](fn: T => U): RandomVariable[U] =
    new RandomVariable(fn(value), densities, batches)

  def zip[U](other: RandomVariable[U]): RandomVariable[(T, U)] =
    for {
      t <- this
      u <- other
    } yield (t, u)

  def condition(fn: T => Real): RandomVariable[T] =
    for {
      t <- this
      _ <- RandomVariable.fromDensity(fn(t))
    } yield t

  def conditionOn[U](seq: Seq[U])(
      implicit ev: T <:< Likelihood[U]): RandomVariable[T] =
    for {
      t <- this
      _ <- ev(t).fit(seq)
    } yield t

  def get[V](implicit rng: RNG,
             sampleable: Sampleable[T, V],
             num: Numeric[Real]): V =
    sampleable.get(value)(rng, num)

  def sample[V]()(implicit rng: RNG, sampleable: Sampleable[T, V]): List[V] =
    sample(Sampler.Default.iterations)

  def sample[V](iterations: Int)(implicit rng: RNG,
                                 sampleable: Sampleable[T, V]): List[V] =
    sample(Sampler.Default.sampler,
           Sampler.Default.warmupIterations,
           iterations)

  def sample[V](sampler: Sampler,
                warmupIterations: Int,
                iterations: Int,
                keepEvery: Int = 1)(implicit rng: RNG,
                                    sampleable: Sampleable[T, V]): List[V] = {
    require(batches.isEmpty)
    val context = Context(density)
    val fn = sampleable.prepare(value, context)
    Sampler
      .sample(context, sampler, warmupIterations, iterations, keepEvery)
      .map { array =>
        fn(array)
      }
  }

  def sampleWithDiagnostics[V](sampler: Sampler,
                               chains: Int,
                               warmupIterations: Int,
                               iterations: Int,
                               parallel: Boolean = true,
                               keepEvery: Int = 1)(
      implicit rng: RNG,
      sampleable: Sampleable[T, V]): (List[V], List[Diagnostics]) = {
    require(batches.isEmpty)
    val context = Context(density)
    val fn = sampleable.prepare(value, context)
    val range = if (parallel) 1.to(chains).par else 1.to(chains)
    val samples =
      range.map { _ =>
        Sampler
          .sample(context, sampler, warmupIterations, iterations, keepEvery)
          .map { array =>
            (array, fn(array))
          }
      }.toList
    val allSamples = samples.flatMap { chain =>
      chain.map(_._2)
    }
    val diagnostics = Sampler.diagnostics(samples.map { chain =>
      chain.map(_._1)
    })
    (allSamples, diagnostics)
  }

  def optimize[V](optimizer: Optimizer, iterations: Int)(
      implicit rng: RNG,
      sampleable: Sampleable[T, V]): V =
    optimize(optimizer, iterations, 1).head

  def optimize[V](optimizer: Optimizer, iterations: Int, samples: Int)(
      implicit rng: RNG,
      sampleable: Sampleable[T, V]): List[V] = {
    val context = Context(density)
    val allBatches = new Batches(batches.toArray.flatMap(_.columns))
    val params = optimizer.optimize(context, allBatches, iterations)
    val fn = sampleable.prepare(value, context)
    1.to(samples)
      .map { _ =>
        fn(params)
      }
      .toList
  }

  lazy val density: Real =
    Real.sum(densities.toList.map(_.toReal))

  //this is really just here to allow destructuring in for{}
  def withFilter(fn: T => Boolean): RandomVariable[T] =
    if (fn(value))
      this
    else
      RandomVariable(value, Real.zero.log)
}

/**
  * The main probability monad used in Rainier for constructing probabilistic programs which can be sampled
  */
object RandomVariable {

  //this exists to provide a reference-equality wrapper
  //for use in the `densities` set
  private class BoxedReal(val toReal: Real)
  private def box(density: Real) = Set(new BoxedReal(density))

  def apply[A](a: A, density: Real, batches: Batches): RandomVariable[A] =
    new RandomVariable(a, box(density), Set(batches))

  def apply[A](a: A, density: Real): RandomVariable[A] =
    new RandomVariable(a, box(density), Set.empty)

  def apply[A](a: A): RandomVariable[A] =
    new RandomVariable(a, box(Real.zero), Set.empty)

  def fromDensity(density: Real): RandomVariable[Unit] =
    new RandomVariable((), box(density), Set.empty)

  def traverse[A](rvs: Seq[RandomVariable[A]]): RandomVariable[Seq[A]] = {

    def go(accum: RandomVariable[Seq[A]], rv: RandomVariable[A]) = {
      for {
        v <- rv
        vs <- accum
      } yield v +: vs
    }

    rvs
      .foldLeft[RandomVariable[Seq[A]]](apply(Seq[A]())) {
        case (accum, elem) => go(accum, elem)
      }
      .map(_.reverse)
  }
}
