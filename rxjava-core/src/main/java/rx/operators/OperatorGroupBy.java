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
package rx.operators;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import rx.Operator;
import rx.observables.GroupedObservable;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;
import rx.util.functions.Action0;
import rx.util.functions.Action1;
import rx.util.functions.Func1;

/**
 * Groups the items emitted by an Observable according to a specified criterion, and emits these
 * grouped items as Observables, one Observable per group.
 * <p>
 * <img width="640" src="https://github.com/Netflix/RxJava/wiki/images/rx-operators/groupBy.png">
 */
public final class OperatorGroupBy<K, T> implements Func1<Operator<? super GroupedObservable<K, T>>, Operator<? super T>> {

    final Func1<? super T, ? extends K> keySelector;

    public OperatorGroupBy(final Func1<? super T, ? extends K> keySelector) {
        this.keySelector = keySelector;
    }

    @Override
    public Operator<? super T> call(final Operator<? super GroupedObservable<K, T>> childOperator) {
        // a new CompositeSubscription to decouple the subscription as the inner subscriptions need a separate lifecycle
        // and will unsubscribe on this parent if they are all unsubscribed
        return new Operator<T>(new CompositeSubscription()) {
            private final Map<K, PublishSubject<T>> groups = new HashMap<K, PublishSubject<T>>();
            private final AtomicInteger completionCounter = new AtomicInteger(0);

            @Override
            public void onCompleted() {
                // if we receive onCompleted from our parent we onComplete children
                for (PublishSubject<T> ps : groups.values()) {
                    ps.onCompleted();
                }

                if (completionCounter.get() == 0) {
                    // special case if no children are running (such as an empty sequence, or just getting the groups and not subscribing)
                    childOperator.onCompleted();
                }
            }

            @Override
            public void onError(Throwable e) {
                // we immediately tear everything down if we receive an error
                childOperator.onError(e);
            }

            @Override
            public void onNext(T t) {
                try {
                    final K key = keySelector.call(t);
                    PublishSubject<T> gps = groups.get(key);
                    if (gps == null) {
                        // this group doesn't exist
                        if (childOperator.isUnsubscribed()) {
                            // we have been unsubscribed on the outer so won't send any  more groups 
                            return;
                        }
                        gps = PublishSubject.create();
                        final PublishSubject<T> _gps = gps;

                        GroupedObservable<K, T> go = new GroupedObservable<K, T>(key, new Action1<Operator<? super T>>() {

                            @Override
                            public void call(final Operator<? super T> o) {
                                // number of children we have running
                                completionCounter.incrementAndGet();
                                o.add(Subscriptions.create(new Action0() {

                                    @Override
                                    public void call() {
                                        completeInner();
                                    }

                                }));
                                _gps.subscribe(new Operator<T>(o) {

                                    @Override
                                    public void onCompleted() {
                                        o.onCompleted();
                                        completeInner();
                                    }

                                    @Override
                                    public void onError(Throwable e) {
                                        o.onError(e);
                                    }

                                    @Override
                                    public void onNext(T t) {
                                        o.onNext(t);
                                    }

                                });
                            }

                        });
                        groups.put(key, gps);
                        childOperator.onNext(go);
                    }
                    // we have the correct group so send value to it
                    gps.onNext(t);
                } catch (Throwable e) {
                    onError(e);
                }
            }

            private void completeInner() {
                if (completionCounter.decrementAndGet() == 0) {
                    unsubscribe();
                    for (PublishSubject<T> ps : groups.values()) {
                        ps.onCompleted();
                    }
                    childOperator.onCompleted();
                }
            }

        };
    }

}
