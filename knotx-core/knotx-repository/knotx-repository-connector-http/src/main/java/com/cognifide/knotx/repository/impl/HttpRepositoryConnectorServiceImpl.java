/*
 * Knot.x - Reactive microservice assembler - Http Repository Connector Verticle
 *
 * Copyright (C) 2016 Cognifide Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cognifide.knotx.repository.impl;

import com.cognifide.knotx.dataobjects.ClientRequest;
import com.cognifide.knotx.dataobjects.ClientResponse;
import com.cognifide.knotx.http.AllowedHeadersFilter;
import com.cognifide.knotx.http.JsonObjectCollector;
import com.cognifide.knotx.http.StringToPatternFunction;
import com.cognifide.knotx.repository.HttpRepositoryConnectorService;
import com.cognifide.knotx.util.MultimapUtil;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.rxjava.core.MultiMap;
import io.vertx.rxjava.core.RxHelper;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.core.http.HttpClient;
import io.vertx.rxjava.core.http.HttpClientResponse;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import rx.Observable;

public class HttpRepositoryConnectorServiceImpl implements HttpRepositoryConnectorService {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpRepositoryConnectorServiceImpl.class);

  private static final String ERROR_MESSAGE = "Unable to get template from the repository";

  private final JsonObject clientOptions;
  private final JsonObject clientDestination;
  private final List<Pattern> allowedRequestHeaders;
  private final HttpClient httpClient;


  public HttpRepositoryConnectorServiceImpl(Vertx vertx, JsonObject configuration) {
    clientOptions = configuration.getJsonObject("clientOptions", new JsonObject());
    clientDestination = configuration.getJsonObject("clientDestination");
    allowedRequestHeaders = configuration.getJsonArray("allowedRequestHeaders", new JsonArray()).stream()
        .map(object -> (String) object)
        .map(new StringToPatternFunction())
        .collect(Collectors.toList());
    httpClient = createHttpClient(vertx);
  }

  @Override
  public void process(ClientRequest request, Handler<AsyncResult<ClientResponse>> result) {
    JsonObject requestHeaders = getFilteredHeaders(request.getHeaders());
    String repoUri = buildRepoUri(request);

    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("GET Http Repository: http://{}:{}{} with headers [{}]",
          clientDestination.getString("domain"),
          clientDestination.getInteger("port"),
          repoUri,
          requestHeaders.encode()
      );
    }

    RxHelper.get(httpClient, clientDestination.getInteger("port"), clientDestination.getString("domain"),
        repoUri, MultiMap.caseInsensitiveMultiMap())
        .doOnNext(this::traceHttpResponse)
        .flatMap(this::processResponse)
        .subscribe(
            response -> result.handle(Future.succeededFuture(response)),
            error -> {
              LOGGER.error(ERROR_MESSAGE, error);
              result.handle(Future.succeededFuture(toErrorResponse()));
            }
        );
  }

  private HttpClient createHttpClient(Vertx vertx) {
    io.vertx.core.http.HttpClient httpClient =
        clientOptions.isEmpty() ? vertx.createHttpClient() : vertx.createHttpClient(new HttpClientOptions(clientOptions));

    return HttpClient.newInstance(httpClient);
  }

  private String buildRepoUri(ClientRequest repoRequest) {
    StringBuilder uri = new StringBuilder(repoRequest.getPath());
    JsonObject params = repoRequest.getParams();
    if (params != null && params.size() > 0) {
      uri.append("?")
          .append(params.stream()
              .map(entry -> new StringBuilder(entry.getKey()).append("=").append(encodeParamValue((String) entry.getValue())))
              .collect(Collectors.joining("&"))
          );
    }

    return uri.toString();
  }

  private String encodeParamValue(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%2F", "/");
    } catch (UnsupportedEncodingException ex) {
      LOGGER.fatal("Unexpected Exception - Unsupported encoding UTF-8", ex);
      throw new UnsupportedCharsetException("UTF-8");
    }
  }

  private Observable<ClientResponse> processResponse(final HttpClientResponse response) {
    return Observable.just(Buffer.buffer())
        .mergeWith(response.toObservable())
        .reduce(Buffer::appendBuffer)
        .map(buffer -> toSuccessResponse(buffer, response));
  }

  private ClientResponse toSuccessResponse(Buffer buffer, final HttpClientResponse httpClientResponse) {
    return new ClientResponse()
        .setStatusCode(httpClientResponse.statusCode())
        .setHeaders(MultimapUtil.toJsonObject(httpClientResponse.headers(), false))
        .setBody((io.vertx.core.buffer.Buffer) buffer.getDelegate());
  }

  private ClientResponse toErrorResponse() {
    return new ClientResponse().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
  }

  private JsonObject getFilteredHeaders(JsonObject headers) {
    return headers.getMap().keySet().stream()
        .filter(AllowedHeadersFilter.create(allowedRequestHeaders))
        .collect(JsonObjectCollector.toJsonObject(o -> o, key -> headers.getJsonArray(key)));
  }

  private void traceHttpResponse(HttpClientResponse response) {
    if (LOGGER.isTraceEnabled()) {
      LOGGER.trace("Got response from remote repository status [{}]", response.statusCode());
    }
  }
}
