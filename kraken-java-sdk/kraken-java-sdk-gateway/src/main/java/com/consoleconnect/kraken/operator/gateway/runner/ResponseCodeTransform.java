package com.consoleconnect.kraken.operator.gateway.runner;

import com.consoleconnect.kraken.operator.core.exception.KrakenException;
import com.consoleconnect.kraken.operator.core.toolkit.JsonToolkit;
import com.consoleconnect.kraken.operator.gateway.model.HttpResponseContext;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public interface ResponseCodeTransform {
  String TARGET_KEY_NOT_FOUND = "targetKey:notFound";

  @Slf4j
  final class LogHolder {}

  default Optional<Integer> checkResponseCode(
      HttpResponseContext httpResponseContext, Object successStatus) {
    if (HttpStatusCode.valueOf(httpResponseContext.getStatus()).is2xxSuccessful()) {
      LogHolder.log.info("The response code is 2xx, checking the value of successStatus.");
      return Optional.of(
          successStatus == null ? httpResponseContext.getStatus() : (int) successStatus);
    }
    if (HttpStatus.NOT_FOUND.value() == httpResponseContext.getStatus()) {
      Object body = httpResponseContext.getBody();
      String bodyContent = JsonToolkit.toJson(body);
      if (StringUtils.isBlank(bodyContent)) {
        throw KrakenException.notFoundDefault();
      }
      throw new KrakenException(httpResponseContext.getStatus(), bodyContent);
    }
    if (HttpStatus.valueOf(httpResponseContext.getStatus()).is4xxClientError()) {
      checkResponseCode(httpResponseContext);
    }
    if (HttpStatus.valueOf(httpResponseContext.getStatus()).is5xxServerError()) {
      checkResponseCode(httpResponseContext);
    }
    return Optional.empty();
  }

  default void checkResponseCode(HttpResponseContext httpResponseContext) {
    Object body = httpResponseContext.getBody();
    String bodyContent = JsonToolkit.toJson(body);
    if (StringUtils.isBlank(bodyContent)) {
      throw new KrakenException(
          httpResponseContext.getStatus(),
          HttpStatus.valueOf(httpResponseContext.getStatus()).getReasonPhrase());
    }
    throw new KrakenException(httpResponseContext.getStatus(), bodyContent);
  }

  default void checkOutputKey(String res) {
    if (StringUtils.isNotBlank(res) && res.contains(TARGET_KEY_NOT_FOUND)) {
      throw new KrakenException(HttpStatus.NOT_FOUND.value(), res);
    }
  }
}