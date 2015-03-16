package com.github.jbarr21.rxplayground;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.text.TextUtils;
import android.view.View;

import java.io.IOException;
import java.util.Collections;

import butterknife.ButterKnife;
import butterknife.OnClick;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class MainActivity extends ActionBarActivity {

    private static final String RX_TEST_TAG = "RxTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.inject(this);
    }

    // Example 1

    @OnClick(R.id.btnChainApiRequests)
    public void onChainApiRequests(View view) {
        // Note: you would normally get the Observable<???Response> objects returned by the Retrofit service like api.fetchSomeThing(params)
        fetchToken("mockId", "mockSecret")
                .flatMap(tokenResp -> authUser(tokenResp.token, "user@foo.com"))
                .flatMap(userResp -> fetchUserDetails(userResp.userId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        detailsResp -> log("save user address: " + detailsResp.userAddress),
                        throwable -> log("Error occurred: " + throwable.getMessage()));
    }

    // Example 2

    @OnClick(R.id.btnParallelWorkToList)
    public void onParallelWorkToList(View view) {
        // Begin test
        int maxNum = 5;
        log("Starting test");

        Observable.range(1, maxNum)
                .flatMap(debug("Generated"))
                .flatMap(num -> simulateLongBlockingWorkObservable(num).subscribeOn(Schedulers.newThread())) // start long work on new thread to get parallel behavior
                .flatMap(debug("Collected num"))
                .toList()
                .doOnNext(list -> log("Collected list"))
                .flatMap(list -> {
                    Collections.reverse(list);
                    return Observable.just(list);
                })
                .doOnNext(list -> log("Reversed list"))
                .subscribeOn(Schedulers.computation())      // start work on comp thread so we don't block main thread
                .observeOn(AndroidSchedulers.mainThread())  // notify subscriber on main thread so we can act on views
                .subscribe(list -> log(String.format("Received list: [%s]", TextUtils.join(", ", list))));

        log("Subscribed");
    }

    // Example 3

    @OnClick(R.id.btnIgnoreErrorProcessingList)
    public void onIgnoreErrorProcessingList(View view) {
        int maxNum = 5;
        Observable.range(1, maxNum)
                .flatMap(debug("Generated"))
                .flatMap(num -> someMethodThatSometimesErrors(num).onErrorResumeNext(Observable.<Integer>empty()))
                .flatMap(debug("Collected num"))
                .toList()
                .doOnNext(list -> log("Collected list"))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        list -> log(String.format("List size is: %d", list.size())),
                        throwable -> log("An error occurred while processing the list"));
    }

    // Example 4

    @OnClick(R.id.btnPerRequestErrorHandling)
    public void onPerRequestErrorHandling(View view) {
        fetchToken("mockId", "mockSecret").onErrorResumeNext(Observable.error(new Exception("Could not fetch token")))
                .flatMap(tokenResp ->
                        authUser(tokenResp.token, "user@foo.com")
                                .onErrorResumeNext(Observable.error(new Exception("Could not auth user")))
                ).flatMap(userResp ->
                        fetchUserDetails(userResp.userId)
                                .onErrorResumeNext(Observable.error(new Exception("Could not fetch user details")))
                ).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        detailsResp -> log("save user address: " + detailsResp.userAddress),
                        throwable -> log("Error occurred: " + throwable.getMessage()));
    }

    // Retrofit simulation

    // simulates a request with retrofit that returns an observable of the type of the response
    private Observable<TokenResponse> fetchToken(final String clientId, final String clientSecret) {
        return Observable.defer(() -> {
            if (Math.random() < 0.1) {
                return Observable.error(new IOException("A random error occurred"));
            }
            log(String.format("fetching token - clientId = %s | clientSecret = %s", clientId, clientSecret));
            return Observable.just(new TokenResponse("fd526a6s4663df0", System.currentTimeMillis()));
        });
    }

    // simulates a request with retrofit that returns an observable of the type of the response
    private Observable<AuthResponse> authUser(String token, String userEmail) {
        return Observable.defer(() -> {
            if (Math.random() < 0.1) {
                return Observable.error(new IOException("A random error occurred"));
            }
            log(String.format("authenticating user %s with token %s", userEmail, token));
            return Observable.just(new AuthResponse(12345678, "foobar123"));
        });
    }

    // simulates a request with retrofit that returns an observable of the type of the response
    private Observable<UserResponse> fetchUserDetails(long userId) {
        return Observable.defer(() -> {
            if (Math.random() < 0.1) {
                return Observable.error(new IOException("A random error occurred"));
            }
            log("fetching user details for id " + userId);
            return Observable.just(new UserResponse("123 Fake St, Springfield, XX, 12345", "http://foo.com/bar.jpg"));
        });
    }

    public static class TokenResponse {
        public String token;
        public long timestamp;
        public TokenResponse(String token, long timestamp) {
            this.token = token;
            this.timestamp = timestamp;
        }
    }

    public static class AuthResponse {
        public long userId;
        public String userName;
        public AuthResponse(long userId, String userName) {
            this.userId = userId;
            this.userName = userName;
        }
    }

    public static class UserResponse {
        public String userAddress;
        public String userImageUrl;
        public UserResponse(String userAddress, String userImageUrl) {
            this.userAddress = userAddress;
            this.userImageUrl = userImageUrl;
        }
    }

    // Utilities

    private Observable<Integer> simulateLongBlockingWorkObservable(int num) {
        return Observable.create(subscriber -> {
            log("Starting long work", num);
            try {
                Thread.sleep((long) (Math.random() * 5 + 3) * 1000);
            } catch (InterruptedException e) {
                // no op
            }
            log("Finished long work", num);

            subscriber.onNext(num);
            subscriber.onCompleted();
        });
    }

    private Observable<Integer> someMethodThatSometimesErrors(int num) {
        return Observable.defer(() -> {
            if (Math.random() > 0.5) {
                log("Method worked", num);
                return Observable.just(num);
            } else {
                log("Method encountered an error", num);
                return Observable.error(new RuntimeException("Error occurred"));
            }
        });
    }

    private Func1<Integer, Observable<Integer>> debug(final String label) {
        return num -> {
            log(label, num);
            return Observable.just(num);
        };
    }

    private void log(String label, int num) {
        Timber.tag(RX_TEST_TAG).d("[%s] Item %d - %s", Thread.currentThread().getName(), num, label);
    }

    private void log(String msg) {
        Timber.tag(RX_TEST_TAG).d("[%s] %s", Thread.currentThread().getName(), msg);
    }
}
