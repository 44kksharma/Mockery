/*
 * Copyright 2016 Victor Albertos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.victoralbertos.mockery.internal.built_in_interceptor;

import com.jakewharton.retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;
import io.victoralbertos.mockery.api.Interceptor;
import io.victoralbertos.mockery.api.Metadata;
import io.victoralbertos.mockery.api.built_in_interceptor.ErrorResponseAdapter;
import io.victoralbertos.mockery.api.built_in_interceptor.Rx2Retrofit;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Response;
import retrofit2.mock.Calls;
import retrofit2.mock.NetworkBehavior;

public final class Rx2RetrofitInterceptor implements Interceptor.Behaviour<Rx2Retrofit> {
  private final CallAdapter callAdapter;

  public Rx2RetrofitInterceptor() {
    this.callAdapter = new CallAdapter(new retrofit2.Retrofit.Builder()
        .baseUrl("http://mockery.com")
        .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
        .build());
  }

  @Override public Observable onLegalMock(final Object mock, final Metadata<Rx2Retrofit> metadata) {
    checkReturnMethodTypeIsObservable(metadata);
    checkTypeMockIsNotObservableNeitherResponse(metadata, mock);

    NetworkBehavior networkBehavior = networkBehaviour(metadata);
    return callAdapter.adapt(metadata.getMethod(),
        networkBehavior, Calls.response(mock));
  }

  @Override public Observable onIllegalMock(final AssertionError assertionError, final Metadata<Rx2Retrofit> metadata) {
    checkReturnMethodTypeIsObservable(metadata);

    final String errorMessage = assertionError.getMessage() != null ? assertionError.getMessage() : "";
    final String adaptedErrorMessage = adaptErrorResponse(errorMessage, metadata);
    NetworkBehavior networkBehavior = networkBehaviour(metadata);

    final ResponseBody responseBody = ResponseBody
        .create(MediaType.parse("application/json"), adaptedErrorMessage);

    return callAdapter.adapt(metadata.getMethod(),
        networkBehavior, Calls.response(Response.error(404, responseBody)));
  }

  @Override public void validate(Object candidate, Metadata<Rx2Retrofit> metadata) throws AssertionError {
    checkReturnMethodTypeIsObservable(metadata);
    Observable observable = (Observable) candidate;

    TestObserver testSubscriber = observable.test();
    testSubscriber.awaitTerminalEvent();
    testSubscriber.assertNoErrors();
    testSubscriber.assertValueCount(1);

    Object enclosingObject = testSubscriber.values().get(0);

    if (enclosingObject instanceof Response) {
      Response response = (Response) enclosingObject;
      assert response.isSuccessful() : "Response must be successful";
      assert response.body() != null : "Body must be not null";
      assert response.errorBody() == null : "Error body must be null";
    }
  }

  @Override public Object adaptResponse(Object response, Metadata<Rx2Retrofit> metadata) {
    checkReturnMethodTypeIsObservable(metadata);
    Observable observable = (Observable) response;

    Object payload = observable.blockingFirst();

    if (payload instanceof Response) {
      Object body = ((Response) payload).body();
      return body;
    }

    return payload;
  }

  @Override public Type adaptType(Type responseType, Metadata<Rx2Retrofit> metadata) {
    checkReturnMethodTypeIsObservable(metadata);

    ParameterizedType observableType = (ParameterizedType) responseType;

    if (isReturnMethodTypeResponse(metadata)) {
      ParameterizedType responseParameterizedType = (ParameterizedType)
          observableType.getActualTypeArguments()[0];
      Type enclosingType = responseParameterizedType.getActualTypeArguments()[0];
      return enclosingType;
    } else {
      Type enclosingType = observableType.getActualTypeArguments()[0];
      return enclosingType;
    }
  }

  private void checkReturnMethodTypeIsObservable(Metadata<Rx2Retrofit> metadata) {
    Type returnMethodType = metadata.getType();

    if (returnMethodType instanceof ParameterizedType) {
      Type type = ((ParameterizedType)returnMethodType).getRawType();
      if (type == Observable.class) return;
    }

    String message = Rx2Messages.illegalMethodReturnType(metadata.getMockingClass(),
        metadata.getMethod(), returnMethodType);
    throw new RuntimeException(message);
  }

  private void checkTypeMockIsNotObservableNeitherResponse(Metadata<Rx2Retrofit> metadata, Object mock) {
    if (mock instanceof Observable) {
      String message = Rx2Messages.illegalMockType(metadata.getMockingClass(),
          metadata.getMethod());
      throw new RuntimeException(message);
    }
  }

  private boolean isReturnMethodTypeResponse(Metadata<Rx2Retrofit> metadata) {
    ParameterizedType observableType = (ParameterizedType) metadata.getType();
    Type enclosingType = observableType.getActualTypeArguments()[0];

    if (enclosingType instanceof ParameterizedType) {
      ParameterizedType parameterizedEnclosingType = (ParameterizedType) enclosingType;
      return parameterizedEnclosingType.getRawType() == Response.class;
    }

    return false;
  }

  private String adaptErrorResponse(String error, Metadata<Rx2Retrofit> metadata) {
    Rx2Retrofit rx2Retrofit = metadata.getAnnotation();

    try {
      Constructor<? extends ErrorResponseAdapter> constructor = rx2Retrofit.errorResponseAdapter()
          .getDeclaredConstructor();
      constructor.setAccessible(true);
      ErrorResponseAdapter errorResponseAdapter = constructor.newInstance();
      return errorResponseAdapter.adapt(error);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private NetworkBehavior networkBehaviour(Metadata<Rx2Retrofit> metadata) {
    Rx2Retrofit retrofit = metadata.getAnnotation();

    NetworkBehavior networkBehavior = NetworkBehavior.create();
    networkBehavior.setDelay(retrofit.delay(), TimeUnit.MILLISECONDS);
    networkBehavior.setVariancePercent(retrofit.variancePercentage());
    networkBehavior.setFailurePercent(retrofit.failurePercent());

    return networkBehavior;
  }

}
