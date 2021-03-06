/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.internal.operators;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.*;
import org.mockito.*;

import rx.*;
import rx.Observable.OnSubscribe;
import rx.exceptions.*;
import rx.functions.*;
import rx.observers.TestSubscriber;
import rx.plugins.RxJavaHooks;

public class OnSubscribeDoOnEachTest {

    @Mock
    Observer<String> subscribedObserver;
    @Mock
    Observer<String> sideEffectObserver;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testDoOnEach() {
        Observable<String> base = Observable.just("a", "b", "c");
        Observable<String> doOnEach = base.doOnEach(sideEffectObserver);

        doOnEach.subscribe(subscribedObserver);

        // ensure the leaf observer is still getting called
        verify(subscribedObserver, never()).onError(any(Throwable.class));
        verify(subscribedObserver, times(1)).onNext("a");
        verify(subscribedObserver, times(1)).onNext("b");
        verify(subscribedObserver, times(1)).onNext("c");
        verify(subscribedObserver, times(1)).onCompleted();

        // ensure our injected observer is getting called
        verify(sideEffectObserver, never()).onError(any(Throwable.class));
        verify(sideEffectObserver, times(1)).onNext("a");
        verify(sideEffectObserver, times(1)).onNext("b");
        verify(sideEffectObserver, times(1)).onNext("c");
        verify(sideEffectObserver, times(1)).onCompleted();
    }

    @Test
    public void testDoOnEachWithError() {
        Observable<String> base = Observable.just("one", "fail", "two", "three", "fail");
        Observable<String> errs = base.map(new Func1<String, String>() {
            @Override
            public String call(String s) {
                if ("fail".equals(s)) {
                    throw new RuntimeException("Forced Failure");
                }
                return s;
            }
        });

        Observable<String> doOnEach = errs.doOnEach(sideEffectObserver);

        doOnEach.subscribe(subscribedObserver);
        verify(subscribedObserver, times(1)).onNext("one");
        verify(subscribedObserver, never()).onNext("two");
        verify(subscribedObserver, never()).onNext("three");
        verify(subscribedObserver, never()).onCompleted();
        verify(subscribedObserver, times(1)).onError(any(Throwable.class));

        verify(sideEffectObserver, times(1)).onNext("one");
        verify(sideEffectObserver, never()).onNext("two");
        verify(sideEffectObserver, never()).onNext("three");
        verify(sideEffectObserver, never()).onCompleted();
        verify(sideEffectObserver, times(1)).onError(any(Throwable.class));
    }

    @Test
    public void testDoOnEachWithErrorInCallback() {
        Observable<String> base = Observable.just("one", "two", "fail", "three");
        Observable<String> doOnEach = base.doOnNext(new Action1<String>() {
            @Override
            public void call(String s) {
                if ("fail".equals(s)) {
                    throw new RuntimeException("Forced Failure");
                }
            }
        });

        doOnEach.subscribe(subscribedObserver);
        verify(subscribedObserver, times(1)).onNext("one");
        verify(subscribedObserver, times(1)).onNext("two");
        verify(subscribedObserver, never()).onNext("three");
        verify(subscribedObserver, never()).onCompleted();
        verify(subscribedObserver, times(1)).onError(any(Throwable.class));

    }

    @Test
    public void testIssue1451Case1() {
        // https://github.com/Netflix/RxJava/issues/1451
        final int expectedCount = 3;
        final AtomicInteger count = new AtomicInteger();
        for (int i=0; i < expectedCount; i++) {
            Observable
                    .just(Boolean.TRUE, Boolean.FALSE)
                    .takeWhile(new Func1<Boolean, Boolean>() {
                        @Override
                        public Boolean call(Boolean value) {
                            return value;
                        }
                    })
                    .toList()
                    .doOnNext(new Action1<List<Boolean>>() {
                        @Override
                        public void call(List<Boolean> booleans) {
                            count.incrementAndGet();
                        }
                    })
                    .subscribe();
        }
        assertEquals(expectedCount, count.get());
    }

    @Test
    public void testIssue1451Case2() {
        // https://github.com/Netflix/RxJava/issues/1451
        final int expectedCount = 3;
        final AtomicInteger count = new AtomicInteger();
        for (int i=0; i < expectedCount; i++) {
            Observable
                    .just(Boolean.TRUE, Boolean.FALSE, Boolean.FALSE)
                    .takeWhile(new Func1<Boolean, Boolean>() {
                        @Override
                        public Boolean call(Boolean value) {
                            return value;
                        }
                    })
                    .toList()
                    .doOnNext(new Action1<List<Boolean>>() {
                        @Override
                        public void call(List<Boolean> booleans) {
                            count.incrementAndGet();
                        }
                    })
                    .subscribe();
        }
        assertEquals(expectedCount, count.get());
    }

    @Test
    public void testFatalError() {
        try {
            Observable.just(1, 2, 3)
                    .flatMap(new Func1<Integer, Observable<?>>() {
                        @Override
                        public Observable<?> call(Integer integer) {
                            return Observable.create(new Observable.OnSubscribe<Object>() {
                                @Override
                                public void call(Subscriber<Object> o) {
                                    throw new NullPointerException("Test NPE");
                                }
                            });
                        }
                    })
                    .doOnNext(new Action1<Object>() {
                        @Override
                        public void call(Object o) {
                            System.out.println("Won't come here");
                        }
                    })
                    .subscribe();
            fail("should have thrown an exception");
        } catch (OnErrorNotImplementedException e) {
            assertTrue(e.getCause() instanceof NullPointerException);
            assertEquals(e.getCause().getMessage(), "Test NPE");
            System.out.println("Received exception: " + e);
        }
    }

    @Test
    public void testOnErrorThrows() {
        TestSubscriber<Object> ts = TestSubscriber.create();

        Observable.error(new TestException())
        .doOnError(new Action1<Throwable>() {
            @Override
            public void call(Throwable e) {
                throw new TestException();
            }
        }).subscribe(ts);

        ts.assertNoValues();
        ts.assertNotCompleted();
        ts.assertError(CompositeException.class);

        CompositeException ex = (CompositeException)ts.getOnErrorEvents().get(0);

        List<Throwable> exceptions = ex.getExceptions();
        assertEquals(2, exceptions.size());
        assertTrue(exceptions.get(0) instanceof TestException);
        assertTrue(exceptions.get(1) instanceof TestException);
    }

    @Test
    public void testIfOnNextActionFailsEmitsErrorAndDoesNotFollowWithCompleted() {
        TestSubscriber<Integer> ts = TestSubscriber.create();
        final RuntimeException e1 = new RuntimeException();
        Observable.create(new OnSubscribe<Integer>() {

            @Override
            public void call(final Subscriber<? super Integer> subscriber) {
                subscriber.setProducer(new Producer() {

                    @Override
                    public void request(long n) {
                        if (n > 0) {
                            subscriber.onNext(1);
                            subscriber.onCompleted();
                        }
                    }});
            }})
            .doOnNext(new Action1<Integer>() {

                @Override
                public void call(Integer t) {
                    throw e1;
                }})
            .unsafeSubscribe(ts);
        ts.assertNoValues();
        ts.assertError(e1);
        ts.assertNotCompleted();
    }

    @Test
    public void testIfOnNextActionFailsEmitsErrorAndDoesNotFollowWithOnNext() {
        TestSubscriber<Integer> ts = TestSubscriber.create();
        final RuntimeException e1 = new RuntimeException();
        Observable.create(new OnSubscribe<Integer>() {

            @Override
            public void call(final Subscriber<? super Integer> subscriber) {
                subscriber.setProducer(new Producer() {

                    @Override
                    public void request(long n) {
                        if (n > 2) {
                            subscriber.onNext(1);
                            subscriber.onNext(2);
                        }
                    }});
            }})
            .doOnNext(new Action1<Integer>() {

                @Override
                public void call(Integer t) {
                    throw e1;
                }})
            .unsafeSubscribe(ts);
        ts.assertNoValues();
        assertEquals(1, ts.getOnErrorEvents().size());
        ts.assertNotCompleted();
    }

    @Test
    public void testIfOnNextActionFailsEmitsErrorAndReportsMoreErrorsToRxJavaHooksNotDownstream() {
        try {
            final List<Throwable> list= new CopyOnWriteArrayList<Throwable>();
            RxJavaHooks.setOnError(new Action1<Throwable>() {

                @Override
                public void call(Throwable e) {
                     list.add(e);
                }});
            TestSubscriber<Integer> ts = TestSubscriber.create();
            final RuntimeException e1 = new RuntimeException();
            final RuntimeException e2 = new RuntimeException();
            Observable.create(new OnSubscribe<Integer>() {

                @Override
                public void call(final Subscriber<? super Integer> subscriber) {
                    subscriber.setProducer(new Producer() {

                        @Override
                        public void request(long n) {
                            if (n > 2) {
                                subscriber.onNext(1);
                                subscriber.onError(e2);
                            }
                        }
                    });
                }
            }).doOnNext(new Action1<Integer>() {

                @Override
                public void call(Integer t) {
                    throw e1;
                }
            }).unsafeSubscribe(ts);
            ts.assertNoValues();
            assertEquals(1, ts.getOnErrorEvents().size());
            ts.assertNotCompleted();
            assertEquals(Arrays.asList(e2), list);
        } finally {
            RxJavaHooks.reset();
        }
    }

    @Test
    public void testIfCompleteActionFailsEmitsError() {
        TestSubscriber<Integer> ts = TestSubscriber.create();
        final RuntimeException e1 = new RuntimeException();
        Observable.<Integer>empty()
            .doOnCompleted(new Action0() {

                @Override
                public void call() {
                    throw e1;
                }})
            .unsafeSubscribe(ts);
        ts.assertNoValues();
        ts.assertError(e1);
        ts.assertNotCompleted();
    }

    @Test
    public void testUnsubscribe() {
        TestSubscriber<Object> ts = TestSubscriber.create(0);
        final AtomicBoolean unsub = new AtomicBoolean();
        Observable.just(1,2,3,4)
           .doOnUnsubscribe(new Action0() {

            @Override
            public void call() {
                unsub.set(true);
            }})
           .doOnNext(Actions.empty())
           .subscribe(ts);
        ts.requestMore(1);
        ts.unsubscribe();
        ts.assertNotCompleted();
        ts.assertValueCount(1);
        assertTrue(unsub.get());
    }

}