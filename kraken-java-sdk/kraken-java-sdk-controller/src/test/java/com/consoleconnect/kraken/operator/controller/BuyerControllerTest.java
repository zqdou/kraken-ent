package com.consoleconnect.kraken.operator.controller;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import com.consoleconnect.kraken.operator.config.TestApplication;
import com.consoleconnect.kraken.operator.controller.dto.BuyerAssetDto;
import com.consoleconnect.kraken.operator.controller.dto.CreateBuyerRequest;
import com.consoleconnect.kraken.operator.core.dto.Tuple2;
import com.consoleconnect.kraken.operator.core.dto.UnifiedAssetDto;
import com.consoleconnect.kraken.operator.core.enums.AssetKindEnum;
import com.consoleconnect.kraken.operator.core.model.HttpResponse;
import com.consoleconnect.kraken.operator.core.service.UnifiedAssetService;
import com.consoleconnect.kraken.operator.core.toolkit.AssetsConstants;
import com.consoleconnect.kraken.operator.core.toolkit.JsonToolkit;
import com.consoleconnect.kraken.operator.core.toolkit.LabelConstants;
import com.consoleconnect.kraken.operator.test.AbstractIntegrationTest;
import com.consoleconnect.kraken.operator.test.MockIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

@Slf4j
@MockIntegrationTest
@ContextConfiguration(classes = {TestApplication.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ActiveProfiles("test-rs256")
class BuyerControllerTest extends AbstractIntegrationTest {
  private static final String PRODUCT_ID = "product.mef.sonata.api";
  public static final String BASE_URL = String.format("/products/%s/buyers", PRODUCT_ID);
  public static final String BUYER_ID = "consolecore-poping-company";

  private final WebTestClientHelper webTestClient;
  @Autowired private UnifiedAssetService unifiedAssetService;

  @Autowired
  public BuyerControllerTest(WebTestClient webTestClient) {
    this.webTestClient = new WebTestClientHelper(webTestClient);
  }

  @Test
  @Order(2)
  void givenBuyer_whenCreate_thenOK() {
    CreateBuyerRequest requestEntity = new CreateBuyerRequest();
    requestEntity.setBuyerId(BUYER_ID);
    requestEntity.setEnvId("stage");
    requestEntity.setCompanyName("console connect");

    String resp =
        webTestClient.requestAndVerify(
            HttpMethod.POST,
            uriBuilder -> uriBuilder.path(BASE_URL).build(),
            HttpStatus.OK.value(),
            requestEntity,
            bodyStr -> {
              assertThat(bodyStr, hasJsonPath("$.data", notNullValue()));
              assertThat(bodyStr, hasJsonPath("$.data.buyerToken", notNullValue()));
              assertThat(bodyStr, hasJsonPath("$.data.buyerToken.accessToken", notNullValue()));
            });
    HttpResponse<BuyerAssetDto> buyerCreatedResp =
        JsonToolkit.fromJson(resp, new TypeReference<HttpResponse<BuyerAssetDto>>() {});
    BuyerAssetDto buyerCreated = buyerCreatedResp.getData();
    String refreshAccessTokenUrl = BASE_URL + "/" + buyerCreated.getId() + "/access-tokens";
    webTestClient.requestAndVerify(
        HttpMethod.POST,
        uriBuilder ->
            uriBuilder
                .path(refreshAccessTokenUrl)
                .queryParam("tokenExpiredInSeconds", "86400")
                .build(),
        HttpStatus.OK.value(),
        null,
        bodyStr -> {
          assertThat(bodyStr, hasJsonPath("$.data", notNullValue()));
          assertThat(bodyStr, hasJsonPath("$.data.buyerToken", notNullValue()));
          assertThat(bodyStr, hasJsonPath("$.data.buyerToken.accessToken", notNullValue()));
        });
  }

  @Test
  @Order(3)
  void givenBuyer_whenSearch_thenOK() {
    webTestClient.requestAndVerify(
        HttpMethod.GET,
        uriBuilder -> uriBuilder.path(BASE_URL).build(),
        HttpStatus.OK.value(),
        null,
        bodyStr -> {
          assertThat(bodyStr, Matchers.notNullValue());
          assertThat(bodyStr, hasJsonPath("$.data.data", hasSize(1)));
        });
  }

  @Test
  @Order(4)
  void givenDuplicatedBuyer_whenCreate_thenNot200() {
    CreateBuyerRequest requestEntity = new CreateBuyerRequest();
    requestEntity.setBuyerId(BUYER_ID);
    requestEntity.setEnvId("stage");
    requestEntity.setCompanyName("console connect");

    webTestClient.requestAndVerify(
        HttpMethod.POST,
        uriBuilder -> uriBuilder.path(BASE_URL).build(),
        HttpStatus.BAD_REQUEST.value(),
        requestEntity,
        bodyStr -> {
          assertThat(bodyStr, hasJsonPath("$.code", not(200)));
        });
  }

  @Test
  @Order(5)
  void givenBlankBuyer_whenCreate_thenNot200() {
    CreateBuyerRequest requestEntity = new CreateBuyerRequest();
    requestEntity.setBuyerId("");
    requestEntity.setEnvId("stage");
    requestEntity.setCompanyName("console connect");
    webTestClient.requestAndVerify(
        HttpMethod.POST,
        uriBuilder -> uriBuilder.path(BASE_URL).build(),
        HttpStatus.BAD_REQUEST.value(),
        requestEntity,
        bodyStr -> {
          assertThat(bodyStr, hasJsonPath("$.code", not(200)));
        });
  }

  @Test
  @Order(6)
  void givenBlankEnv_whenCreateBuyer_thenNot200() {
    CreateBuyerRequest requestEntity = new CreateBuyerRequest();
    requestEntity.setBuyerId(BUYER_ID);
    requestEntity.setEnvId("");
    requestEntity.setCompanyName("console connect");
    webTestClient.requestAndVerify(
        HttpMethod.POST,
        uriBuilder -> uriBuilder.path(BASE_URL).build(),
        HttpStatus.BAD_REQUEST.value(),
        requestEntity,
        bodyStr -> {
          assertThat(bodyStr, hasJsonPath("$.code", not(200)));
        });
  }

  @Test
  @Order(7)
  void givenCreateBuyer_whenDeactivate_thenOK() {
    UnifiedAssetDto buyerAsset =
        unifiedAssetService
            .findBySpecification(
                Tuple2.ofList(AssetsConstants.FIELD_KIND, AssetKindEnum.PRODUCT_BUYER.getKind()),
                Tuple2.ofList(LabelConstants.LABEL_BUYER_ID, BUYER_ID),
                null,
                null,
                null)
            .getData()
            .get(0);
    webTestClient.requestAndVerify(
        HttpMethod.POST,
        uriBuilder ->
            uriBuilder
                .path("/products/{productId}/buyers/{id}/deactivate")
                .build(PRODUCT_ID, buyerAsset.getId()),
        HttpStatus.OK.value(),
        null,
        bodyStr -> {
          assertThat(bodyStr, hasJsonPath("$.code", is(200)));
        });
  }

  @Test
  @Order(8)
  void givenBuyerDeactivate_whenActivate_thenOK() {
    UnifiedAssetDto buyerAsset =
        unifiedAssetService
            .findBySpecification(
                Tuple2.ofList(AssetsConstants.FIELD_KIND, AssetKindEnum.PRODUCT_BUYER.getKind()),
                Tuple2.ofList(LabelConstants.LABEL_BUYER_ID, BUYER_ID),
                null,
                null,
                null)
            .getData()
            .get(0);
    webTestClient.requestAndVerify(
        HttpMethod.POST,
        uriBuilder ->
            uriBuilder
                .path("/products/{productId}/buyers/{id}/activate")
                .build(PRODUCT_ID, buyerAsset.getId()),
        HttpStatus.OK.value(),
        null,
        bodyStr -> {
          assertThat(bodyStr, hasJsonPath("$.code", is(200)));
        });
  }
}
