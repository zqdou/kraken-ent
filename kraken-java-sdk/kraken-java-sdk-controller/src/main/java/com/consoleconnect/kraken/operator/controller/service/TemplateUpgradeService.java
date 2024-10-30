package com.consoleconnect.kraken.operator.controller.service;

import static com.consoleconnect.kraken.operator.core.enums.AssetKindEnum.PRODUCT_TEMPLATE_DEPLOYMENT;
import static com.consoleconnect.kraken.operator.core.enums.AssetLinkKindEnum.IMPLEMENTATION_TARGET_MAPPER;

import com.consoleconnect.kraken.operator.auth.security.UserContext;
import com.consoleconnect.kraken.operator.auth.service.UserService;
import com.consoleconnect.kraken.operator.controller.dto.*;
import com.consoleconnect.kraken.operator.controller.enums.SystemStateEnum;
import com.consoleconnect.kraken.operator.controller.event.TemplateSynCompletedEvent;
import com.consoleconnect.kraken.operator.controller.event.TemplateUpgradeEvent;
import com.consoleconnect.kraken.operator.controller.model.*;
import com.consoleconnect.kraken.operator.controller.service.upgrade.UpgradeSourceService;
import com.consoleconnect.kraken.operator.controller.service.upgrade.UpgradeSourceServiceFactory;
import com.consoleconnect.kraken.operator.core.dto.Tuple2;
import com.consoleconnect.kraken.operator.core.dto.UnifiedAssetDto;
import com.consoleconnect.kraken.operator.core.entity.AssetLinkEntity;
import com.consoleconnect.kraken.operator.core.entity.UnifiedAssetEntity;
import com.consoleconnect.kraken.operator.core.enums.AssetKindEnum;
import com.consoleconnect.kraken.operator.core.enums.DeployStatusEnum;
import com.consoleconnect.kraken.operator.core.enums.MappingStatusEnum;
import com.consoleconnect.kraken.operator.core.enums.ReleaseKindEnum;
import com.consoleconnect.kraken.operator.core.event.IngestDataEvent;
import com.consoleconnect.kraken.operator.core.event.IngestionDataResult;
import com.consoleconnect.kraken.operator.core.exception.KrakenException;
import com.consoleconnect.kraken.operator.core.ingestion.DataIngestionJob;
import com.consoleconnect.kraken.operator.core.model.AssetLink;
import com.consoleconnect.kraken.operator.core.model.Metadata;
import com.consoleconnect.kraken.operator.core.model.SyncMetadata;
import com.consoleconnect.kraken.operator.core.model.UnifiedAsset;
import com.consoleconnect.kraken.operator.core.model.facet.ComponentAPITargetFacets;
import com.consoleconnect.kraken.operator.core.repo.UnifiedAssetRepository;
import com.consoleconnect.kraken.operator.core.service.UnifiedAssetService;
import com.consoleconnect.kraken.operator.core.toolkit.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
@Slf4j
public class TemplateUpgradeService {
  private final UnifiedAssetService unifiedAssetService;
  private final EnvironmentService environmentService;
  private final ProductDeploymentService productDeploymentService;
  private final UserService userService;
  private final SystemInfoService systemInfoService;
  private final UpgradeSourceServiceFactory upgradeSourceServiceFactory;
  private final MgmtProperty mgmtProperty;
  private final DataIngestionJob dataIngestionJob;
  private final UnifiedAssetRepository unifiedAssetRepository;
  private final ComponentTagService componentTagService;

  public static final int PAGE_SIZE = 200;
  private final ApiComponentService apiComponentService;

  protected String deployProduction(
      String templateUpgradeId, String stageEnvId, String productionEnvId, String userId) {
    // find stage template deployment
    UnifiedAssetDto stageDeployment = findStageDeployment(templateUpgradeId, stageEnvId);
    UnifiedAssetDto productAsset = getProductAsset();
    TemplateUpgradeDeploymentFacets templateUpgradeDeploymentFacets4Stage =
        UnifiedAsset.getFacets(stageDeployment, TemplateUpgradeDeploymentFacets.class);
    Environment environment = environmentService.findOne(productionEnvId);
    // new deployment for production
    UnifiedAsset templateUpgradeDeployment =
        newTemplateUpgradeDeployment(environment, templateUpgradeId, PRODUCT_TEMPLATE_DEPLOYMENT);
    TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment =
        new TemplateUpgradeDeploymentFacets.EnvDeployment();
    // clone
    cloneMapperDeployment(
        templateUpgradeDeploymentFacets4Stage, productAsset, envDeployment, environment);
    TemplateUpgradeDeploymentFacets templateUpgradeDeploymentFacets =
        new TemplateUpgradeDeploymentFacets();
    templateUpgradeDeploymentFacets.setEnvDeployment(envDeployment);
    Map<String, Object> facets =
        JsonToolkit.fromJson(
            JsonToolkit.toJson(templateUpgradeDeploymentFacets), new TypeReference<>() {});
    templateUpgradeDeployment.setFacets(facets);
    IngestionDataResult ingestionDataResult =
        unifiedAssetService.syncAsset(
            templateUpgradeId,
            templateUpgradeDeployment,
            new SyncMetadata("", "", DateTime.nowInUTCString(), userId),
            true);
    addLabels(envDeployment, ingestionDataResult.getData().getId().toString());
    return ingestionDataResult.getData().getId().toString();
  }

  private void cloneMapperDeployment(
      TemplateUpgradeDeploymentFacets templateUpgradeDeploymentFacets4Stage,
      UnifiedAssetDto productAsset,
      TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment,
      Environment production) {
    templateUpgradeDeploymentFacets4Stage
        .getEnvDeployment()
        .getMapperDeployment()
        .forEach(
            deploymentId -> {
              UnifiedAssetDto assetDto = unifiedAssetService.findOne(deploymentId);
              DeploymentFacet deploymentFacet =
                  UnifiedAsset.getFacets(assetDto, DeploymentFacet.class);
              for (ComponentTag componentTag : deploymentFacet.getComponentTags()) {
                CreateProductDeploymentRequest request = new CreateProductDeploymentRequest();
                request.setTagIds(List.of(componentTag.getTagId()));
                request.setEnvId(production.getId());
                UnifiedAssetDto unifiedAssetDto =
                    productDeploymentService.create(
                        productAsset.getMetadata().getKey(),
                        request,
                        ReleaseKindEnum.API_LEVEL,
                        getSystemUpgradeUser(),
                        true);
                envDeployment.getMapperDeployment().add(unifiedAssetDto.getId());
              }
            });
    templateUpgradeDeploymentFacets4Stage
        .getEnvDeployment()
        .getSystemDeployments()
        .forEach(
            deploymentId -> {
              UnifiedAssetDto assetDto = unifiedAssetService.findOne(deploymentId);
              DeploymentFacet deploymentFacet =
                  UnifiedAsset.getFacets(assetDto, DeploymentFacet.class);
              for (ComponentTag componentTag : deploymentFacet.getComponentTags()) {
                CreateProductDeploymentRequest request = new CreateProductDeploymentRequest();
                request.setTagIds(List.of(componentTag.getTagId()));
                request.setEnvId(production.getId());
                UnifiedAssetDto unifiedAssetDto =
                    productDeploymentService.create(
                        productAsset.getMetadata().getKey(),
                        request,
                        ReleaseKindEnum.SYSTEM_TEMPLATE_MIXED,
                        getSystemUpgradeUser(),
                        false);
                envDeployment.getSystemDeployments().add(unifiedAssetDto.getId());
              }
            });
  }

  /**
   * May existed multiple stage template deployment
   *
   * @param templateUpgradeId
   * @param stageEnvId
   * @return
   */
  private UnifiedAssetDto findStageDeployment(String templateUpgradeId, String stageEnvId) {
    List<UnifiedAssetDto> list =
        unifiedAssetService
            .findBySpecification(
                Tuple2.ofList(
                    AssetsConstants.FIELD_KIND,
                    AssetKindEnum.PRODUCT_TEMPLATE_DEPLOYMENT.getKind()),
                Tuple2.ofList(
                    LabelConstants.LABEL_ENV_ID,
                    stageEnvId,
                    LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID,
                    templateUpgradeId),
                null,
                PageRequest.of(0, 1, Sort.Direction.DESC, AssetsConstants.FIELD_CREATE_AT),
                null)
            .getData();
    assert CollectionUtils.isNotEmpty(list);
    return list.get(0);
  }

  public void addLabels(
      TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment,
      String templateUpgradeDeploymentId) {
    Stream.concat(
            envDeployment.getMapperDeployment().stream(),
            envDeployment.getSystemDeployments().stream())
        .filter(Objects::nonNull)
        .forEach(
            deploymentId ->
                unifiedAssetService.addLabel(
                    deploymentId,
                    LabelConstants.LABEL_APP_TEMPLATE_DEPLOYMENT_ID,
                    templateUpgradeDeploymentId));
  }

  public UnifiedAssetDto getProductAsset() {
    Paging<UnifiedAssetDto> assetDtoPaging =
        unifiedAssetService.findBySpecification(
            List.of(new Tuple2(AssetsConstants.FIELD_KIND, AssetKindEnum.PRODUCT.getKind())),
            null,
            null,
            null,
            null);
    return assetDtoPaging.getData().get(0);
  }

  public UnifiedAsset newTemplateUpgradeDeployment(
      Environment environment, String templateUpgradeId, AssetKindEnum deploymentKind) {
    String key = deploymentKind.getKind() + ":" + DateTime.nowInUTCString();
    UnifiedAsset templateUpgradeDeployment =
        UnifiedAsset.of(deploymentKind.getKind(), key, "template upgrade");
    if (environment != null) {
      templateUpgradeDeployment
          .getMetadata()
          .getLabels()
          .put(LabelConstants.LABEL_ENV_ID, environment.getId());
      templateUpgradeDeployment
          .getMetadata()
          .getLabels()
          .put(LabelConstants.LABEL_ENV_NAME, environment.getName());
    }
    templateUpgradeDeployment
        .getMetadata()
        .getLabels()
        .put(LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID, templateUpgradeId);
    return templateUpgradeDeployment;
  }

  public Paging<TemplateUpgradeDeploymentVO> listTemplateDeployment(
      String templateUpgradeIdParam, PageRequest pageRequest) {
    Paging<UnifiedAssetDto> templateDeploymentsPaging =
        unifiedAssetService.findBySpecification(
            Tuple2.ofList(
                AssetsConstants.FIELD_KIND, AssetKindEnum.PRODUCT_TEMPLATE_DEPLOYMENT.getKind()),
            Optional.ofNullable(templateUpgradeIdParam)
                .map(t -> Tuple2.ofList(LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID, t))
                .orElse(null),
            null,
            pageRequest,
            null);
    List<String> templateUpgradeIds =
        templateDeploymentsPaging.getData().stream()
            .map(
                dto ->
                    dto.getMetadata().getLabels().get(LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID))
            .filter(StringUtils::isNotBlank)
            .toList();
    Map<String, String> templateUpgradeMap =
        unifiedAssetService.findAllByIdIn(templateUpgradeIds).stream()
            .collect(
                Collectors.toMap(
                    t -> t.getId().toString(),
                    t -> t.getLabels().get(LabelConstants.LABEL_PRODUCT_VERSION)));
    List<TemplateUpgradeDeploymentVO> voList =
        templateDeploymentsPaging.getData().stream()
            .map(
                dto -> {
                  String templateUpgradeId =
                      dto.getMetadata()
                          .getLabels()
                          .get(LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID);
                  String envName = dto.getMetadata().getLabels().get(LabelConstants.LABEL_ENV_NAME);
                  TemplateUpgradeDeploymentVO templateUpgradeDeploymentVO =
                      new TemplateUpgradeDeploymentVO();
                  templateUpgradeDeploymentVO.setTemplateUpgradeId(templateUpgradeId);
                  templateUpgradeDeploymentVO.setUpgradeBy(dto.getCreatedBy());
                  templateUpgradeDeploymentVO.setEnvName(envName);
                  templateUpgradeDeploymentVO.setProductVersion(
                      templateUpgradeMap.get(templateUpgradeId));
                  templateUpgradeDeploymentVO.setStatus(dto.getMetadata().getStatus());
                  templateUpgradeDeploymentVO.setDeploymentId(dto.getId());
                  templateUpgradeDeploymentVO.setCreatedAt(dto.getCreatedAt());
                  return templateUpgradeDeploymentVO;
                })
            .toList();
    return PagingHelper.toPageNoSubList(
        voList,
        templateDeploymentsPaging.getPage(),
        templateDeploymentsPaging.getSize(),
        templateDeploymentsPaging.getTotal());
  }

  public List<MapperTagVO> templateDeploymentDetails(String templateDeploymentId) {
    UnifiedAssetDto templateDeployment = unifiedAssetService.findOne(templateDeploymentId);
    TemplateUpgradeDeploymentFacets upgradeDeploymentFacets =
        UnifiedAsset.getFacets(templateDeployment, TemplateUpgradeDeploymentFacets.class);
    List<MapperTagVO> draftList =
        upgradeDeploymentFacets.getEnvDeployment().getMapperDraft().stream()
            .map(
                mapperKey -> {
                  MapperTagVO mapperTagVO = new MapperTagVO();
                  mapperTagVO.setMapperKey(mapperKey);
                  List<AssetLinkEntity> assetLink =
                      unifiedAssetService.findAssetLink(
                          mapperKey, IMPLEMENTATION_TARGET_MAPPER.getKind());
                  AssetLinkEntity assetLinkEntity = assetLink.get(0);
                  mapperTagVO.setComponentKey(assetLinkEntity.getAsset().getKey());
                  UnifiedAssetDto mapperAsset = unifiedAssetService.findOne(mapperKey);
                  ComponentAPITargetFacets mapperFacets =
                      UnifiedAsset.getFacets(mapperAsset, ComponentAPITargetFacets.class);
                  ComponentExpandDTO.MappingMatrix mappingMatrix =
                      new ComponentExpandDTO.MappingMatrix();
                  BeanUtils.copyProperties(mapperFacets.getTrigger(), mappingMatrix);
                  mapperTagVO.setMappingMatrix(mappingMatrix);
                  mapperTagVO.setStatus(DeployStatusEnum.DRAFT.name());
                  mapperTagVO.setPath(mapperFacets.getTrigger().getPath());
                  mapperTagVO.setMethod(mapperFacets.getTrigger().getMethod());
                  return mapperTagVO;
                })
            .toList();
    List<MapperTagVO> deployedList =
        upgradeDeploymentFacets.getEnvDeployment().getMapperDeployment().stream()
            .flatMap(
                mapperDeploymentId -> {
                  UnifiedAssetDto mapperDeployment =
                      unifiedAssetService.findOne(mapperDeploymentId);
                  DeploymentFacet facets =
                      UnifiedAsset.getFacets(mapperDeployment, DeploymentFacet.class);
                  return facets.getComponentTags().stream()
                      .map(
                          tag -> {
                            UnifiedAssetDto assetDto = unifiedAssetService.findOne(tag.getTagId());
                            ComponentTagFacet componentTagFacet =
                                UnifiedAsset.getFacets(assetDto, ComponentTagFacet.class);
                            UnifiedAssetDto component = componentTagFacet.getComponent();
                            UnifiedAssetDto mapperAsset =
                                componentTagFacet.getChildren().stream()
                                    .filter(
                                        asset ->
                                            asset
                                                .getKind()
                                                .equalsIgnoreCase(
                                                    AssetKindEnum.COMPONENT_API_TARGET_MAPPER
                                                        .getKind()))
                                    .findFirst()
                                    .orElse(null);
                            MapperTagVO mapperTagVO = new MapperTagVO();
                            mapperTagVO.setTagId(tag.getTagId());
                            mapperTagVO.setMapperKey(mapperAsset.getMetadata().getKey());
                            mapperTagVO.setComponentKey(component.getMetadata().getKey());
                            mapperTagVO.setVersion(
                                assetDto
                                    .getMetadata()
                                    .getLabels()
                                    .get(LabelConstants.LABEL_VERSION_NAME));
                            ComponentAPITargetFacets mapperFacets =
                                UnifiedAsset.getFacets(mapperAsset, ComponentAPITargetFacets.class);
                            ComponentExpandDTO.MappingMatrix mappingMatrix =
                                new ComponentExpandDTO.MappingMatrix();
                            BeanUtils.copyProperties(mapperFacets.getTrigger(), mappingMatrix);
                            mapperTagVO.setMappingMatrix(mappingMatrix);
                            mapperTagVO.setStatus(mapperDeployment.getMetadata().getStatus());
                            mapperTagVO.setPath(mapperFacets.getTrigger().getPath());
                            mapperTagVO.setMethod(mapperFacets.getTrigger().getMethod());
                            return mapperTagVO;
                          });
                })
            .toList();
    List<MapperTagVO> resultList = Lists.newArrayList();
    resultList.addAll(deployedList);
    resultList.addAll(draftList);
    return resultList;
  }

  public List<TemplateUpgradeDeploymentVO> currentUpgradeVersion() {
    return environmentService.findAll().stream()
        .map(
            environment ->
                unifiedAssetService
                    .findBySpecification(
                        Tuple2.ofList(
                            AssetsConstants.FIELD_KIND, PRODUCT_TEMPLATE_DEPLOYMENT.getKind(),
                            AssetsConstants.FIELD_STATUS, DeployStatusEnum.SUCCESS.name()),
                        Tuple2.ofList(LabelConstants.LABEL_ENV_ID, environment.getId()),
                        null,
                        PageRequest.of(0, 1, Sort.Direction.DESC, AssetsConstants.FIELD_CREATE_AT),
                        null)
                    .getData()
                    .stream()
                    .findFirst()
                    .map(
                        templateDeployment -> {
                          String templateUpgradeId =
                              templateDeployment
                                  .getMetadata()
                                  .getLabels()
                                  .get(LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID);
                          String version =
                              unifiedAssetService
                                  .findOne(templateUpgradeId)
                                  .getMetadata()
                                  .getLabels()
                                  .get(LabelConstants.LABEL_PRODUCT_VERSION);
                          TemplateUpgradeDeploymentVO templateUpgradeDeploymentVO =
                              new TemplateUpgradeDeploymentVO();
                          templateUpgradeDeploymentVO.setEnvId(environment.getId());
                          templateUpgradeDeploymentVO.setEnvName(environment.getName());
                          templateUpgradeDeploymentVO.setTemplateUpgradeId(templateUpgradeId);
                          templateUpgradeDeploymentVO.setProductVersion(version);
                          templateUpgradeDeploymentVO.setTemplateUpgradeDeploymentId(
                              templateDeployment.getId());
                          return templateUpgradeDeploymentVO;
                        })
                    .orElse(null))
        .filter(Objects::nonNull)
        .toList();
  }

  public void checkCondition2StageUpgrade(String templateUpgradeId, String envId) {
    Paging<UnifiedAssetDto> assetDtoPaging = findLatestTemplateUpgrade();
    if (CollectionUtils.isEmpty(assetDtoPaging.getData())) {
      throw KrakenException.badRequest("no upgrade found");
    }
    UnifiedAssetDto templateUpgrade = assetDtoPaging.getData().get(0);
    if (!templateUpgrade.getId().equalsIgnoreCase(templateUpgradeId)) {
      throw KrakenException.badRequest("upgrade is not the latest ,operation is not allowed");
    }
    Paging<UnifiedAssetDto> deploymentPage =
        unifiedAssetService.findBySpecification(
            Tuple2.ofList(AssetsConstants.FIELD_KIND, PRODUCT_TEMPLATE_DEPLOYMENT.getKind()),
            Tuple2.ofList(
                LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID,
                templateUpgradeId,
                LabelConstants.LABEL_ENV_ID,
                envId),
            null,
            PageRequest.of(0, 1),
            null);
    checkStatus(
        deploymentPage,
        dto -> {
          if (List.of(DeployStatusEnum.IN_PROCESS.name(), DeployStatusEnum.SUCCESS.name())
              .contains(dto.getMetadata().getStatus())) {
            throw KrakenException.badRequest("upgrade is already completed");
          }
        });
  }

  public void checkCondition2ProductionUpgrade(CreateProductionUpgradeRequest upgradeRequest) {
    Paging<UnifiedAssetDto> assetDtoPaging = findLatestTemplateUpgrade();
    if (CollectionUtils.isEmpty(assetDtoPaging.getData())) {
      throw KrakenException.badRequest("no upgrade found");
    }
    UnifiedAssetDto templateUpgrade = assetDtoPaging.getData().get(0);
    if (!templateUpgrade.getId().equalsIgnoreCase(upgradeRequest.getTemplateUpgradeId())) {
      throw KrakenException.badRequest("upgrade is not the latest ,operation is not allowed");
    }
    Paging<UnifiedAssetDto> deploymentPage =
        unifiedAssetService.findBySpecification(
            Tuple2.ofList(AssetsConstants.FIELD_KIND, PRODUCT_TEMPLATE_DEPLOYMENT.getKind()),
            Tuple2.ofList(
                LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID,
                upgradeRequest.getTemplateUpgradeId(),
                LabelConstants.LABEL_ENV_ID,
                upgradeRequest.getStageEnvId()),
            null,
            PageRequest.of(0, 1),
            null);
    checkStatus(
        deploymentPage,
        dto -> {
          if (!DeployStatusEnum.SUCCESS.name().equalsIgnoreCase(dto.getMetadata().getStatus())) {
            throw KrakenException.badRequest("stage upgrade possibly not completed");
          }
        });
    Paging<UnifiedAssetDto> productionDeploymentPage =
        unifiedAssetService.findBySpecification(
            Tuple2.ofList(AssetsConstants.FIELD_KIND, PRODUCT_TEMPLATE_DEPLOYMENT.getKind()),
            Tuple2.ofList(
                LabelConstants.LABEL_APP_TEMPLATE_UPGRADE_ID,
                upgradeRequest.getTemplateUpgradeId(),
                LabelConstants.LABEL_ENV_ID,
                upgradeRequest.getProductEnvId()),
            null,
            PageRequest.of(0, 1),
            null);
    checkStatus(
        productionDeploymentPage,
        dto -> {
          if (List.of(DeployStatusEnum.IN_PROCESS.name(), DeployStatusEnum.SUCCESS.name())
              .contains(dto.getMetadata().getStatus())) {
            throw KrakenException.badRequest("production upgrade already existed");
          }
        });
  }

  private void checkStatus(
      Paging<UnifiedAssetDto> paging, Consumer<UnifiedAssetDto> deployStatusConsumer) {
    Optional.ofNullable(paging)
        .map(Paging::getData)
        .filter(CollectionUtils::isNotEmpty)
        .map(ListUtils::getFirst)
        .ifPresent(deployStatusConsumer);
  }

  private Paging<UnifiedAssetDto> findLatestTemplateUpgrade() {
    return unifiedAssetService.findBySpecification(
        Tuple2.ofList(AssetsConstants.FIELD_KIND, AssetKindEnum.PRODUCT_TEMPLATE_UPGRADE.getKind()),
        null,
        null,
        PageRequest.of(0, 1, Sort.Direction.DESC, AssetsConstants.FIELD_CREATE_AT),
        null);
  }

  public String getSystemUpgradeUser() {
    return userService.findOneByIdOrEmail(UserContext.SYSTEM_UPGRADE).getId().toString();
  }

  @Transactional(rollbackFor = Exception.class)
  public String controlPlaneUpgradeV3(String templateUpgradeId, String userId) {
    return this.controlPlaneUpgrade(templateUpgradeId, userId);
  }

  public String controlPlaneUpgrade(String templateUpgradeId, String userId) {
    SystemInfo systemInfo = systemInfoService.find();
    if (!SystemStateEnum.CAN_UPGRADE_STATES.contains(systemInfo.getStatus())) {
      throw KrakenException.badRequest("Current system state is not allowed to upgrade");
    }
    // upgrading
    systemInfoService.updateSystemStatus(SystemStateEnum.CONTROL_PLANE_UPGRADING);
    UpgradeSourceService upgradeSourceService =
        upgradeSourceServiceFactory.getUpgradeSourceService(templateUpgradeId);
    List<UpgradeTuple> upgradeTuples =
        upgradeSourceService.getTemplateUpgradeRecords(templateUpgradeId);
    UnifiedAssetDto productAsset = this.getProductAsset();
    UpgradeTuple upgradeTuple = upgradeTuples.get(0);
    // direct save
    upgradeTuple
        .directSaves()
        .forEach(directSaved -> ingestData(upgradeTuple.productKey(), directSaved, false));
    // compare from version
    upgradeTuple
        .versionChangedTemplates()
        .forEach(
            versionRecord -> ingestData(productAsset.getMetadata().getKey(), versionRecord, false));
    // config from template upgrade path
    upgradeTuple
        .enforceUpgradeTemplates()
        .forEach(
            upgradeRecord -> ingestData(productAsset.getMetadata().getKey(), upgradeRecord, true));
    // update version
    upgradeMapperVersion();
    UnifiedAsset controlUpgradeDeployment =
        this.newTemplateUpgradeDeployment(
            null, templateUpgradeId, AssetKindEnum.PRODUCT_TEMPLATE_CONTROL_DEPLOYMENT);
    controlUpgradeDeployment.setFacets(Map.of(ControlDeploymentFacet.KEY, upgradeTuple));
    SyncMetadata syncMetadata = new SyncMetadata("", "", "");
    syncMetadata.setSyncedBy(userId);
    IngestionDataResult ingestionDataResult =
        unifiedAssetService.syncAsset(
            productAsset.getId(), controlUpgradeDeployment, syncMetadata, true);
    systemInfoService.update(
        SystemStateEnum.CONTROL_PLANE_UPGRADE_DONE,
        getTemplateVersion(templateUpgradeId),
        null,
        null);
    if (ingestionDataResult.getCode() != 200) {
      throw KrakenException.internalError(
          "Control plane upgraded failed:" + ingestionDataResult.getMessage());
    }
    return ingestionDataResult.getData().getId().toString();
  }

  @Transactional
  public String stageUpgrade(TemplateUpgradeEvent event) {
    UpgradeSourceService upgradeSourceService =
        upgradeSourceServiceFactory.getUpgradeSourceService(event.getTemplateUpgradeId());
    log.info("Template upgrade  Event Received, event class:{}", event.getClass());
    String controlDeploymentId =
        controlPlaneUpgrade(event.getTemplateUpgradeId(), event.getUserId());
    UnifiedAssetDto controlDeployment = unifiedAssetService.findOne(controlDeploymentId);
    ControlDeploymentFacet controlDeploymentFacet =
        UnifiedAsset.getFacets(controlDeployment, ControlDeploymentFacet.class);
    UpgradeTuple upgradeTuple = controlDeploymentFacet.getUpgradeTuple();
    TemplateSynCompletedEvent templateSynCompletedEvent = new TemplateSynCompletedEvent();
    HashSet<UpgradeRecord> upgradeRecords = new HashSet<>(upgradeTuple.versionChangedTemplates());
    upgradeRecords.addAll(upgradeTuple.enforceUpgradeTemplates());
    Collection<UpgradeRecord> values =
        upgradeRecords.stream()
            .collect(
                Collectors.groupingBy(
                    UpgradeRecord::key,
                    Collectors.collectingAndThen(Collectors.toList(), list -> list.get(0))))
            .values();
    List<UpgradeRecord> finalList = new ArrayList<>(values);
    templateSynCompletedEvent.setTemplateUpgradeRecords(finalList);
    templateSynCompletedEvent.setEnvId(event.getEnvId());
    templateSynCompletedEvent.setTemplateUpgradeId(event.getTemplateUpgradeId());
    templateSynCompletedEvent.setUserId(event.getUserId());
    IngestionDataResult ingestionDataResult = this.deployStage(templateSynCompletedEvent);
    String deploymentId = ingestionDataResult.getData().getId().toString();
    if (StringUtils.isNotBlank(deploymentId)) {
      // report result
      upgradeSourceService.reportResult(event.getTemplateUpgradeId(), deploymentId);
      log.info("Template upgrade  completed");
      return deploymentId;
    }
    throw KrakenException.internalError("Template upgrade error:Unknown reason");
  }

  protected void upgradeMapperVersion() {
    unifiedAssetRepository
        .findByKindOrderByCreatedAtDesc(AssetKindEnum.COMPONENT_API_TARGET_MAPPER.getKind())
        .forEach(
            mapper -> {
              mapper
                  .getLabels()
                  .put(LabelConstants.LABEL_SUB_VERSION_NAME, NumberUtils.INTEGER_ONE.toString());
              unifiedAssetRepository.save(mapper);
            });
  }

  private String getTemplateVersion(String templateUpgradeId) {
    UnifiedAssetDto templateUpgrade = unifiedAssetService.findOne(templateUpgradeId);
    return templateUpgrade
        .getMetadata()
        .getLabels()
        .get(LabelConstants.LABEL_PRODUCT_VERSION)
        .replaceFirst("[V|v]", "");
  }

  protected void ingestData(String parentKey, UpgradeRecord upgradeRecord, boolean enforce) {
    boolean mergeLabels =
        mgmtProperty.getTemplateUpgrade().getMergeLabelKinds().contains(upgradeRecord.kind());
    IngestDataEvent event =
        new IngestDataEvent(
            parentKey, upgradeRecord.fullPath(), mergeLabels, this.getSystemUpgradeUser());
    event.setEnforceSync(enforce);
    dataIngestionJob.ingestData(event);
  }

  protected IngestionDataResult deployStage(TemplateSynCompletedEvent event) {
    List<String> keyList =
        new ArrayList<>(
            event.getTemplateUpgradeRecords().stream().map(UpgradeRecord::key).toList());
    if (CollectionUtils.isEmpty(keyList)) {
      return IngestionDataResult.of(200, "");
    }
    Paging<UnifiedAssetDto> componentAssetDtoPaging =
        getUnifiedAssetDtoPaging(AssetKindEnum.COMPONENT_API.getKind());

    Map<String, String> link2ComponentMap = Maps.newHashMap();
    componentAssetDtoPaging
        .getData()
        .forEach(
            asset -> {
              for (AssetLink link : asset.getLinks()) {
                link2ComponentMap.put(link.getTargetAssetKey(), asset.getMetadata().getKey());
              }
            });
    Set<String> changedMappers = new HashSet<>();
    Set<String> dealSet = new HashSet<>();

    Map<String, List<Tuple2>> apiUseCase = apiComponentService.findApiUseCase();

    keyList.forEach(
        key ->
            apiComponentService
                .findRelatedApiUse(key, apiUseCase)
                .ifPresent(
                    relatedApiUse -> {
                      changedMappers.add(relatedApiUse.getMapperKey());
                      dealSet.add(relatedApiUse.getComponentApiKey());
                      dealSet.addAll(relatedApiUse.membersExcludeApiKey());
                    }));
    keyList.removeIf(dealSet::contains);
    UnifiedAssetDto productAsset = this.getProductAsset();
    UnifiedAssetDto assetDto = unifiedAssetService.findOne(event.getTemplateUpgradeId());
    TemplateUpgradeDeploymentFacets templateUpgradeDeploymentFacets =
        UnifiedAsset.getFacets(assetDto, TemplateUpgradeDeploymentFacets.class);
    TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment =
        new TemplateUpgradeDeploymentFacets.EnvDeployment();
    templateUpgradeDeploymentFacets.setEnvDeployment(envDeployment);
    envDeployment.setEnvId(event.getEnvId());
    // build auto release for system template
    if (CollectionUtils.isNotEmpty(keyList)) {
      List<UnifiedAssetDto> finalAssets = generateDeployedAssets(keyList);
      String deploymentId =
          deploySystemTemplateDeployment(productAsset, finalAssets, event.getEnvId());
      envDeployment.setSystemDeployments(Collections.singletonList(deploymentId));
    }
    if (CollectionUtils.isNotEmpty(changedMappers)) {
      deployMapperDeployment(
          link2ComponentMap, productAsset, changedMappers, event.getEnvId(), envDeployment);
    }

    return sinkStageTemplateDeployment(
        event, assetDto, templateUpgradeDeploymentFacets, envDeployment);
  }

  private Paging<UnifiedAssetDto> getUnifiedAssetDtoPaging(String kind) {
    return unifiedAssetService.search(null, kind, true, null, PageRequest.of(0, PAGE_SIZE));
  }

  private List<UnifiedAssetDto> generateDeployedAssets(List<String> keyList) {
    List<UnifiedAssetDto> finalAssets = unifiedAssetService.findByAllKeysIn(keyList, true);
    List<UnifiedAssetEntity> parentAssets =
        unifiedAssetService.findAllByIdIn(
            finalAssets.stream()
                .map(UnifiedAssetDto::getParentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    Map<String, String> id2KeyMap =
        parentAssets.stream()
            .collect(
                Collectors.toMap(entity -> entity.getId().toString(), UnifiedAssetEntity::getKey));
    finalAssets.forEach(t -> t.setParentId(id2KeyMap.get(t.getParentId())));
    return finalAssets;
  }

  private String deploySystemTemplateDeployment(
      UnifiedAssetDto productAsset, List<UnifiedAssetDto> finalAssets, String envId) {

    IngestionDataResult systemTemplateTag =
        componentTagService.createSystemTemplateTag(
            productAsset.getMetadata().getKey(), finalAssets);
    CreateProductDeploymentRequest createProductDeploymentRequest =
        new CreateProductDeploymentRequest();
    createProductDeploymentRequest.setTagIds(
        Collections.singletonList(systemTemplateTag.getData().getId().toString()));
    createProductDeploymentRequest.setEnvId(envId);
    String format = DateTime.format(ZonedDateTime.now());
    createProductDeploymentRequest.setDescription("system-template:" + format);
    UnifiedAssetDto unifiedAssetDto =
        productDeploymentService.deployComponents(
            productAsset.getMetadata().getKey(),
            createProductDeploymentRequest,
            ReleaseKindEnum.SYSTEM_TEMPLATE_MIXED,
            this.getSystemUpgradeUser(),
            false);
    return unifiedAssetDto.getId();
  }

  private void deployMapperDeployment(
      Map<String, String> link2ComponentMap,
      UnifiedAsset product,
      Set<String> changedMappers,
      String envId,
      TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment) {

    List<String> deploymentIds = new ArrayList<>();
    List<UnifiedAssetDto> byAllKeysIn =
        unifiedAssetService.findByAllKeysIn(new ArrayList<>(changedMappers), false);
    byAllKeysIn.stream()
        .filter(
            ent ->
                MapUtils.isNotEmpty(ent.getMetadata().getLabels())
                    && LabelConstants.VALUE_DEPLOYED_STATUS_DEPLOYED.equalsIgnoreCase(
                        ent.getMetadata().getLabels().get(LabelConstants.LABEL_DEPLOYED_STATUS)))
        .map(UnifiedAssetDto::getMetadata)
        .map(Metadata::getKey)
        .forEach(
            mapperKey -> {
              CreateAPIMapperDeploymentRequest request = new CreateAPIMapperDeploymentRequest();
              request.setMapperKeys(Collections.singletonList(mapperKey));
              request.setComponentId(link2ComponentMap.get(mapperKey));
              request.setEnvId(envId);
              UnifiedAssetDto mapperVersionAndDeploy =
                  productDeploymentService.createMapperVersionAndDeploy(
                      product.getMetadata().getKey(),
                      request,
                      ReleaseKindEnum.API_LEVEL,
                      this.getSystemUpgradeUser(),
                      true);
              deploymentIds.add(mapperVersionAndDeploy.getId());
            });
    if (CollectionUtils.isNotEmpty(deploymentIds)) {
      envDeployment.setMapperDeployment(deploymentIds);
    }
    List<String> draftMappers =
        byAllKeysIn.stream()
            .filter(
                ent ->
                    !(MapUtils.isNotEmpty(ent.getMetadata().getLabels())
                        && LabelConstants.VALUE_DEPLOYED_STATUS_DEPLOYED.equalsIgnoreCase(
                            ent.getMetadata()
                                .getLabels()
                                .get(LabelConstants.LABEL_DEPLOYED_STATUS))))
            .map(UnifiedAssetDto::getMetadata)
            .map(Metadata::getKey)
            .toList();
    if (CollectionUtils.isNotEmpty(draftMappers)) {
      envDeployment.setMapperDraft(draftMappers);
    }
  }

  public String stageUpgradeV3(TemplateUpgradeEvent event) {
    SystemInfo systemInfo = systemInfoService.find();
    if (!SystemStateEnum.CAN_UPGRADE_STATES.contains(systemInfo.getStatus())) {
      throw KrakenException.badRequest("The current system status does not support upgrade");
    }
    List<ApiMapperDeploymentDTO> stageRunningMappers =
        productDeploymentService.listRunningApiMapperDeploymentV3(event.getEnvId());
    List<String> runningMapperKeys =
        stageRunningMappers.stream().map(ApiMapperDeploymentDTO::getTargetMapperKey).toList();
    boolean existedInCompleted =
        apiComponentService.listAllApiUseCase().stream()
            .flatMap(t -> t.getDetails().stream())
            .filter(t -> runningMapperKeys.contains(t.getTargetMapperKey()))
            .anyMatch(
                t -> t.getMappingStatus().equalsIgnoreCase(MappingStatusEnum.INCOMPLETE.getDesc()));
    if (existedInCompleted) {
      throw KrakenException.badRequest(
          "Not allowed to upgrade:There is an incomplete mapping mapper");
    }
    systemInfoService.updateSystemStatus(SystemStateEnum.STAGE_UPGRADING);
    TemplateSynCompletedEvent templateSynCompletedEvent = new TemplateSynCompletedEvent();
    List<UpgradeRecord> upgradeRecords =
        runningMapperKeys.stream()
            .map(
                key ->
                    new UpgradeRecord(
                        key, AssetKindEnum.COMPONENT_API_TARGET_MAPPER.getKind(), 1, ""))
            .toList();
    templateSynCompletedEvent.setTemplateUpgradeRecords(upgradeRecords);
    templateSynCompletedEvent.setEnvId(event.getEnvId());
    templateSynCompletedEvent.setTemplateUpgradeId(event.getTemplateUpgradeId());
    try {
      IngestionDataResult ingestionDataResult = this.deployStageV3(templateSynCompletedEvent);
      String deploymentId = ingestionDataResult.getData().getId().toString();
      if (StringUtils.isNotBlank(deploymentId)) {
        // report result
        upgradeSourceServiceFactory
            .getUpgradeSourceService(event.getTemplateUpgradeId())
            .reportResult(event.getTemplateUpgradeId(), deploymentId);
        log.info("Template upgrade  completed");
        return deploymentId;
      }
    } catch (Exception exception) {
      log.error("Template upgrade failed", exception);
      systemInfoService.updateSystemStatus(SystemStateEnum.STAGE_UPGRADE_DONE);
      throw KrakenException.internalError(exception.getMessage());
    }

    throw KrakenException.internalError("Template upgrade error:Unknown reason");
  }

  protected IngestionDataResult deployStageV3(TemplateSynCompletedEvent event) {
    List<String> keyList =
        new ArrayList<>(
            event.getTemplateUpgradeRecords().stream().map(UpgradeRecord::key).toList());
    if (CollectionUtils.isEmpty(keyList)) {
      return IngestionDataResult.of(200, "");
    }
    Paging<UnifiedAssetDto> componentAssetDtoPaging =
        getUnifiedAssetDtoPaging(AssetKindEnum.COMPONENT_API.getKind());

    Map<String, String> link2ComponentMap = Maps.newHashMap();
    componentAssetDtoPaging
        .getData()
        .forEach(
            asset -> {
              for (AssetLink link : asset.getLinks()) {
                link2ComponentMap.put(link.getTargetAssetKey(), asset.getMetadata().getKey());
              }
            });

    UnifiedAssetDto productAsset =
        unifiedAssetService.findByKind(AssetKindEnum.PRODUCT.getKind()).get(0);
    UnifiedAssetDto assetDto = unifiedAssetService.findOne(event.getTemplateUpgradeId());
    TemplateUpgradeDeploymentFacets templateUpgradeDeploymentFacets =
        UnifiedAsset.getFacets(assetDto, TemplateUpgradeDeploymentFacets.class);
    TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment =
        new TemplateUpgradeDeploymentFacets.EnvDeployment();
    templateUpgradeDeploymentFacets.setEnvDeployment(envDeployment);
    envDeployment.setEnvId(event.getEnvId());
    if (CollectionUtils.isNotEmpty(keyList)) {
      deployMapperDeployment(
          link2ComponentMap, productAsset, new HashSet<>(keyList), event.getEnvId(), envDeployment);
    }

    return sinkStageTemplateDeployment(
        event, assetDto, templateUpgradeDeploymentFacets, envDeployment);
  }

  private IngestionDataResult sinkStageTemplateDeployment(
      TemplateSynCompletedEvent event,
      UnifiedAssetDto assetDto,
      TemplateUpgradeDeploymentFacets templateUpgradeDeploymentFacets,
      TemplateUpgradeDeploymentFacets.EnvDeployment envDeployment) {
    Environment environment = environmentService.findOne(event.getEnvId());
    UnifiedAsset templateUpgradeDeployment =
        this.newTemplateUpgradeDeployment(
            environment, event.getTemplateUpgradeId(), AssetKindEnum.PRODUCT_TEMPLATE_DEPLOYMENT);

    Map<String, Object> facets =
        JsonToolkit.fromJson(
            JsonToolkit.toJson(templateUpgradeDeploymentFacets), new TypeReference<>() {});
    templateUpgradeDeployment.setFacets(facets);
    if (CollectionUtils.isEmpty(envDeployment.getMapperDeployment())
        && CollectionUtils.isEmpty(envDeployment.getSystemDeployments())) {
      templateUpgradeDeployment.getMetadata().setStatus(DeployStatusEnum.SUCCESS.name());
    } else {
      templateUpgradeDeployment.getMetadata().setStatus(DeployStatusEnum.IN_PROCESS.name());
    }

    IngestionDataResult ingestionDataResult =
        unifiedAssetService.syncAsset(
            assetDto.getId(),
            templateUpgradeDeployment,
            new SyncMetadata("", "", DateTime.nowInUTCString(), event.getUserId()),
            true);
    UUID id = ingestionDataResult.getData().getId();
    this.addLabels(envDeployment, id.toString());
    return ingestionDataResult;
  }

  @Transactional(rollbackFor = Exception.class)
  public String deployProductionV3(
      String templateUpgradeId, String stageEnvId, String productionEnvId, String userId) {
    SystemInfo systemInfo = systemInfoService.find();
    if (SystemStateEnum.STAGE_UPGRADE_DONE.name().equalsIgnoreCase(systemInfo.getStatus())) {
      throw KrakenException.badRequest(
          "System state is:" + systemInfo.getStatus() + ". Not allowed to production upgrade");
    }
    return deployProduction(templateUpgradeId, stageEnvId, productionEnvId, userId);
  }

  @Transactional(rollbackFor = Exception.class)
  public String deployProductionV2(
      String templateUpgradeId, String stageEnvId, String productionEnvId, String userId) {
    return deployProduction(templateUpgradeId, stageEnvId, productionEnvId, userId);
  }

  public List<ComponentExpandDTO> listApiUseCase(String templateUpgradeId) {
    return upgradeSourceServiceFactory
        .getUpgradeSourceService(templateUpgradeId)
        .listApiUseCases(templateUpgradeId);
  }
}
