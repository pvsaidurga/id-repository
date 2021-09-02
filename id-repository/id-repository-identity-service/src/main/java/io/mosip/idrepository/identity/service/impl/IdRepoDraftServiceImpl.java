package io.mosip.idrepository.identity.service.impl;

import static io.mosip.idrepository.core.constant.IdRepoConstants.DOT;
import static io.mosip.idrepository.core.constant.IdRepoConstants.EXTRACTION_FORMAT_QUERY_PARAM_SUFFIX;
import static io.mosip.idrepository.core.constant.IdRepoConstants.MOSIP_KERNEL_IDREPO_JSON_PATH;
import static io.mosip.idrepository.core.constant.IdRepoConstants.ROOT_PATH;
import static io.mosip.idrepository.core.constant.IdRepoConstants.SPLITTER;
import static io.mosip.idrepository.core.constant.IdRepoConstants.UIN_REFID;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.DATABASE_ACCESS_ERROR;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.FILE_STORAGE_ACCESS_ERROR;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.NO_RECORD_FOUND;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.RECORD_EXISTS;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.UIN_GENERATION_FAILED;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.UIN_HASH_MISMATCH;
import static io.mosip.idrepository.core.constant.IdRepoErrorConstants.UNKNOWN_ERROR;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.hibernate.exception.JDBCConnectionException;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import io.mosip.idrepository.core.builder.RestRequestBuilder;
import io.mosip.idrepository.core.constant.RestServicesConstants;
import io.mosip.idrepository.core.dto.AnonymousProfileDTO;
import io.mosip.idrepository.core.dto.DocumentsDTO;
import io.mosip.idrepository.core.dto.IdRequestDTO;
import io.mosip.idrepository.core.dto.IdResponseDTO;
import io.mosip.idrepository.core.dto.RequestDTO;
import io.mosip.idrepository.core.dto.ResponseDTO;
import io.mosip.idrepository.core.dto.RestRequestDTO;
import io.mosip.idrepository.core.exception.IdRepoAppException;
import io.mosip.idrepository.core.exception.IdRepoAppUncheckedException;
import io.mosip.idrepository.core.exception.IdRepoDataValidationException;
import io.mosip.idrepository.core.exception.RestServiceException;
import io.mosip.idrepository.core.helper.RestHelper;
import io.mosip.idrepository.core.logger.IdRepoLogger;
import io.mosip.idrepository.core.security.IdRepoSecurityManager;
import io.mosip.idrepository.core.spi.IdRepoDraftService;
import io.mosip.idrepository.core.util.DataValidationUtil;
import io.mosip.idrepository.identity.entity.Uin;
import io.mosip.idrepository.identity.entity.UinBiometric;
import io.mosip.idrepository.identity.entity.UinBiometricDraft;
import io.mosip.idrepository.identity.entity.UinDocument;
import io.mosip.idrepository.identity.entity.UinDocumentDraft;
import io.mosip.idrepository.identity.entity.UinDraft;
import io.mosip.idrepository.identity.repository.UinBiometricRepo;
import io.mosip.idrepository.identity.repository.UinDocumentRepo;
import io.mosip.idrepository.identity.repository.UinDraftRepo;
import io.mosip.idrepository.identity.validator.IdRequestValidator;
import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.StringUtils;

/**
 * @author Manoj SP
 *
 */
@Service
@Transactional(rollbackFor = { IdRepoAppException.class, IdRepoAppUncheckedException.class })
public class IdRepoDraftServiceImpl extends IdRepoServiceImpl implements IdRepoDraftService<IdRequestDTO, IdResponseDTO> {

	private static final String GET_DRAFT = "getDraft";

	private static final String DISCARD_DRAFT = "discardDraft";

	private static final String PUBLISH_DRAFT = "publishDraft";

	private static final String DRAFTED = "DRAFTED";

	private static final String UPDATE_DRAFT = "UpdateDraft";

	private static final String GENERATE_UIN = "generateUin";

	private static final String CREATE_DRAFT = "createDraft";

	private static final String ID_REPO_DRAFT_SERVICE_IMPL = "IdRepoDraftServiceImpl";

	private static final Logger mosipLogger = IdRepoLogger.getLogger(IdRepoDraftServiceImpl.class);

	@Value("${" + MOSIP_KERNEL_IDREPO_JSON_PATH + "}")
	private String uinPath;

	@Value("${" + UIN_REFID + "}")
	private String uinRefId;

	@Autowired
	private UinDraftRepo uinDraftRepo;

	@Autowired
	private IdRequestValidator validator;

	@Autowired
	private RestRequestBuilder restBuilder;

	@Autowired
	private RestHelper restHelper;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private UinBiometricRepo uinBiometricRepo;

	@Autowired
	private UinDocumentRepo uinDocumentRepo;
	
	@Autowired
	private IdRepoProxyServiceImpl proxyService;

	@Override
	public IdResponseDTO createDraft(String registrationId, String uin) throws IdRepoAppException {
		try {
			if (!super.uinHistoryRepo.existsByRegId(registrationId) && !uinDraftRepo.existsByRegId(registrationId)) {
				UinDraft newDraft;
				if (Objects.nonNull(uin)) {
					int modValue = getModValue(uin);
					if (super.uinRepo.existsByUinHash(super.getUinHash(uin, modValue))) {
						Uin uinObject = super.uinRepo.findByUinHash(super.getUinHash(uin, modValue));
						newDraft = mapper.convertValue(uinObject, UinDraft.class);
						updateBiometricAndDocumentDrafts(registrationId, newDraft, uinObject);
						newDraft.setRegId(registrationId);
						newDraft.setUin(super.getUinToEncrypt(uin, super.getModValue(uin)));
					} else {
						mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, CREATE_DRAFT,
								"UIN NOT EXIST");
						throw new IdRepoAppException(NO_RECORD_FOUND);
					}
				} else {
					newDraft = new UinDraft();
					uin = generateUin();
					int modValue = getModValue(uin);
					newDraft.setUin(super.getUinToEncrypt(uin, modValue));
					newDraft.setUinHash(super.getUinHash(uin, modValue));
					byte[] uinData = convertToBytes(generateIdentityObject(uin));
					newDraft.setUinData(uinData);
					newDraft.setUinDataHash(securityManager.hash(uinData));
				}
				newDraft.setRegId(registrationId);
				newDraft.setStatusCode("DRAFT");
				newDraft.setCreatedBy(IdRepoSecurityManager.getUser());
				newDraft.setCreatedDateTime(DateUtils.getUTCCurrentDateTime());
				uinDraftRepo.save(newDraft);
				return constructIdResponse(null, DRAFTED, null, null);
			} else {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, CREATE_DRAFT, "RID ALREADY EXIST");
				throw new IdRepoAppException(RECORD_EXISTS);
			}
		} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, CREATE_DRAFT, e.getMessage());
			throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
		}
	}

	private Object generateIdentityObject(Object uin) {
		List<String> pathList = new ArrayList<>(Arrays.asList("identity.UIN".split("\\.")));
		pathList.remove(ROOT_PATH);
		Collections.reverse(pathList);
		for (String string : pathList) {
			uin = new HashMap<>(Map.of(string, uin));
		}
		return uin;
	}

	private String generateUin() throws IdRepoAppException {
		try {
			RestRequestDTO restRequest = restBuilder.buildRequest(RestServicesConstants.UIN_GENERATOR_SERVICE, null,
					ResponseWrapper.class);
			ResponseWrapper<Map<String, String>> response = restHelper.requestSync(restRequest);
			return response.getResponse().get("uin");
		} catch (IdRepoDataValidationException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GENERATE_UIN, e.getMessage());
			throw new IdRepoAppException(UNKNOWN_ERROR, e);
		} catch (RestServiceException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GENERATE_UIN, e.getMessage());
			throw new IdRepoAppException(UIN_GENERATION_FAILED, e);
		}
	}

	@Override
	public IdResponseDTO updateDraft(String registrationId, IdRequestDTO request) throws IdRepoAppException {
		try {
			Optional<UinDraft> uinDraft = uinDraftRepo.findByRegId(registrationId);
			if (uinDraft.isPresent()) {
				UinDraft draftToUpdate = uinDraft.get();
				if (Objects.isNull(draftToUpdate.getUinData())) {
					byte[] uinData = super.convertToBytes(request.getRequest().getIdentity());
					draftToUpdate.setUinData(uinData);
					draftToUpdate.setUinDataHash(securityManager.hash(uinData));
					updateDocuments(request.getRequest(), draftToUpdate);
					draftToUpdate.setUpdatedBy(IdRepoSecurityManager.getUser());
					draftToUpdate.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
					uinDraftRepo.save(draftToUpdate);
				} else {
					super.updateAnonymousProfile(request, draftToUpdate);
					updateDemographicData(request, draftToUpdate);
					updateDocuments(request.getRequest(), draftToUpdate);

					uinDraftRepo.save(draftToUpdate);
				}
			} else {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, UPDATE_DRAFT,
						"RID NOT FOUND IN DB");
				throw new IdRepoAppException(NO_RECORD_FOUND);
			}
		} catch (JSONException | InvalidJsonException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, UPDATE_DRAFT, e.getMessage());
			throw new IdRepoAppException(UNKNOWN_ERROR, e);
		} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, UPDATE_DRAFT, e.getMessage());
			throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
		}
		return constructIdResponse(null, DRAFTED, null, null);
	}

	private void updateDemographicData(IdRequestDTO request, UinDraft draftToUpdate) throws JSONException, IdRepoAppException {
		if (Objects.nonNull(request.getRequest()) && Objects.nonNull(request.getRequest().getIdentity())) {
			RequestDTO requestDTO = request.getRequest();
			Configuration configuration = Configuration.builder().jsonProvider(new JacksonJsonProvider())
					.mappingProvider(new JacksonMappingProvider()).build();
			DocumentContext inputData = JsonPath.using(configuration).parse(requestDTO.getIdentity());
			DocumentContext dbData = JsonPath.using(configuration).parse(new String(draftToUpdate.getUinData()));
			JsonPath uinJsonPath = JsonPath.compile(uinPath.replace(ROOT_PATH, "$"));
			inputData.set(uinJsonPath, dbData.read(uinJsonPath));
			JSONCompareResult comparisonResult = JSONCompare.compareJSON(inputData.jsonString(), dbData.jsonString(),
					JSONCompareMode.LENIENT);

			if (comparisonResult.failed()) {
				super.updateJsonObject(inputData, dbData, comparisonResult);
				draftToUpdate.setUinData(convertToBytes(convertToObject(dbData.jsonString().getBytes(), Map.class)));
				draftToUpdate.setUinDataHash(securityManager.hash(draftToUpdate.getUinData()));
				draftToUpdate.setUpdatedBy(IdRepoSecurityManager.getUser());
				draftToUpdate.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
			}
		}
	}

	private void updateDocuments(RequestDTO requestDTO, UinDraft draftToUpdate) throws IdRepoAppException {
		if (Objects.nonNull(requestDTO.getDocuments()) && !requestDTO.getDocuments().isEmpty()) {
			Uin uinObject = mapper.convertValue(draftToUpdate, Uin.class);
			String uinHashWithSalt = draftToUpdate.getUinHash().split(SPLITTER)[1];
			super.updateDocuments(uinHashWithSalt, uinObject, requestDTO, true);
			updateBiometricAndDocumentDrafts(requestDTO.getRegistrationId(), draftToUpdate, uinObject);
		}
	}

	private void updateBiometricAndDocumentDrafts(String regId, UinDraft draftToUpdate, Uin uinObject) {
		List<UinBiometric> uinBiometrics = new ArrayList<>(uinObject.getBiometrics());
		IntStream.range(0, uinBiometrics.size()).forEach(index -> {
			UinBiometric uinBio = uinBiometrics.get(index);
			Optional<UinBiometricDraft> draftBioRecord = draftToUpdate.getBiometrics().stream()
					.filter(draftBio -> uinBio.getBiometricFileType().contentEquals(draftBio.getBiometricFileType())).findFirst();
			if (draftBioRecord.isPresent()) {
				UinBiometricDraft draftBio = draftBioRecord.get();
				if (!uinBio.getBioFileId().contentEquals(draftBio.getBioFileId())) {
					draftBio.setRegId(regId);
					draftBio.setBioFileId(uinBio.getBioFileId());
					draftBio.setBiometricFileName(uinBio.getBiometricFileName());
					draftBio.setBiometricFileHash(uinBio.getBiometricFileHash());
					draftBio.setUpdatedBy(IdRepoSecurityManager.getUser());
					draftBio.setUpdatedDateTime(DateUtils.getUTCCurrentDateTime());
				}
				ListIterator<UinBiometric> listIterator = uinObject.getBiometrics().listIterator();
				while (listIterator.hasNext()) {
					if (listIterator.next().getBioFileId().contentEquals(draftBio.getBioFileId()))
						listIterator.remove();
				}
			}
		});

		List<UinDocument> uinDocuments = new ArrayList<>(uinObject.getDocuments());
		IntStream.range(0, uinDocuments.size()).forEach(index -> {
			UinDocument uinDoc = uinDocuments.get(index);
			Optional<UinDocumentDraft> draftDocRecord = draftToUpdate.getDocuments().stream()
					.filter(draftDoc -> uinDoc.getDoccatCode().contentEquals(draftDoc.getDoccatCode())).findFirst();
			if (draftDocRecord.isPresent()) {
				UinDocumentDraft draftDoc = draftDocRecord.get();
				if (!uinDoc.getDocId().contentEquals(draftDoc.getDocId())) {
					draftDoc.setRegId(regId);
					draftDoc.setDocId(uinDoc.getDocId());
					draftDoc.setDoctypCode(uinDoc.getDoctypCode());
					draftDoc.setDocName(uinDoc.getDocName());
					draftDoc.setDocfmtCode(uinDoc.getDocfmtCode());
					draftDoc.setDocHash(uinDoc.getDocHash());
					draftDoc.setUpdatedBy(IdRepoSecurityManager.getUser());
					draftDoc.setUpdatedDateTime(uinDoc.getUpdatedDateTime());
				}
				ListIterator<UinDocument> listIterator = uinObject.getDocuments().listIterator();
				while (listIterator.hasNext()) {
					if (listIterator.next().getDocId().contentEquals(draftDoc.getDocId()))
						listIterator.remove();
				}
			}
		});

		List<UinBiometricDraft> bioDraftList = mapper.convertValue(uinObject.getBiometrics(),
				new TypeReference<List<UinBiometricDraft>>() {
				});
		List<UinDocumentDraft> docDraftList = mapper.convertValue(uinObject.getDocuments(),
				new TypeReference<List<UinDocumentDraft>>() {
				});
		draftToUpdate.getBiometrics().addAll(bioDraftList);
		draftToUpdate.getDocuments().addAll(docDraftList);
		draftToUpdate.getBiometrics().forEach(bio -> bio.setRegId(regId));
		draftToUpdate.getDocuments().forEach(doc -> doc.setRegId(regId));
	}

	@Override
	public IdResponseDTO publishDraft(String regId) throws IdRepoAppException {
		try {
			Optional<UinDraft> uinDraft = uinDraftRepo.findByRegId(regId);
			if (uinDraft.isEmpty()) {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, PUBLISH_DRAFT,
						"DRAFT RECORD NOT FOUND");
				throw new IdRepoAppException(NO_RECORD_FOUND);
			} else {
				UinDraft draft = uinDraft.get();
				IdRequestDTO idRequest = buildRequest(regId, draft);
				validateRequest(idRequest.getRequest());
				String uin = decryptUin(draft.getUin(), draft.getUinHash());
				final Uin uinObject;
				if (uinRepo.existsByUinHash(draft.getUinHash())) {
					uinObject = super.updateIdentity(idRequest, uin);
				} else {
					uinObject = super.addIdentity(idRequest, uin);
				}
				publishDocuments(draft, uinObject);
				this.discardDraft(regId);
				return constructIdResponse(null, uinObject.getStatusCode(), null, null);
			}
		} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, PUBLISH_DRAFT, e.getMessage());
			throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
		}
	}

	private IdRequestDTO buildRequest(String regId, UinDraft draft) throws IdRepoAppException {
		try {
			IdRequestDTO idRequest = new IdRequestDTO();
			RequestDTO request = new RequestDTO();
			request.setRegistrationId(regId);
			request.setAnonymousProfile(Objects.nonNull(draft.getAnonymousProfile())
					? mapper.readValue(draft.getAnonymousProfile(), AnonymousProfileDTO.class)
					: null);
			request.setIdentity(convertToObject(draft.getUinData(), Object.class));
			idRequest.setRequest(request);
			return idRequest;
		} catch (IOException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, PUBLISH_DRAFT, e.getMessage());
			throw new IdRepoAppException(UNKNOWN_ERROR);
		}
	}

	private void validateRequest(RequestDTO request) throws IdRepoDataValidationException {
		Errors errors = new BeanPropertyBindingResult(new IdRequestDTO(), "idRequestDto");
		validator.validateRequest(request, errors, "create");
		DataValidationUtil.validate(errors);
	}

	private void publishDocuments(UinDraft draft, final Uin uinObject) {
		List<UinBiometric> uinBiometricList = draft.getBiometrics().stream().map(bio -> {
			UinBiometric uinBio = mapper.convertValue(bio, UinBiometric.class);
			uinBio.setUinRefId(uinObject.getUinRefId());
			uinBio.setLangCode("");
			return uinBio;
		}).collect(Collectors.toList());
		uinBiometricRepo.saveAll(uinBiometricList);
		List<UinDocument> uinDocumentList = draft.getDocuments().stream().map(doc -> {
			UinDocument uinDoc = mapper.convertValue(doc, UinDocument.class);
			uinDoc.setUinRefId(uinObject.getUinRefId());
			uinDoc.setLangCode("");
			return uinDoc;
		}).collect(Collectors.toList());
		uinDocumentRepo.saveAll(uinDocumentList);
	}

	private String decryptUin(String encryptedUin, String uinHash) throws IdRepoAppException {
		String salt = uinEncryptSaltRepo.getOne(Integer.valueOf(encryptedUin.split(SPLITTER)[0])).getSalt();
		String uin = new String(securityManager.decryptWithSalt(
				CryptoUtil.decodeBase64(StringUtils.substringAfter((String) encryptedUin, SPLITTER)),
				CryptoUtil.decodeBase64(salt), uinRefId));
		if (!StringUtils.equals(super.getUinHash(uin, super.getModValue(uin)), uinHash)) {
			throw new IdRepoAppUncheckedException(UIN_HASH_MISMATCH);
		}
		return uin;
	}

	@Override
	public IdResponseDTO discardDraft(String regId) throws IdRepoAppException {
		try {
			uinDraftRepo.findByRegId(regId).ifPresent(uinDraftRepo::delete);
			return constructIdResponse(null, "DISCARDED", null, null);
		} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, DISCARD_DRAFT, e.getMessage());
			throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
		}
	}

	@Override
	public boolean hasDraft(String regId) throws IdRepoAppException {
		try {
			return uinDraftRepo.existsByRegId(regId);
		} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, "hasDraft", e.getMessage());
			throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
		}
	}

	@Override
	public IdResponseDTO getDraft(String regId) throws IdRepoAppException {
		try {
			Optional<UinDraft> uinDraft = uinDraftRepo.findByRegId(regId);
			if (uinDraft.isPresent()) {
				UinDraft draft = uinDraft.get();
				List<DocumentsDTO> documents = new ArrayList<>();
				String uinHash = draft.getUinHash().split(SPLITTER)[1];
				AnonymousProfileDTO anonymousProfile = Objects.nonNull(draft.getAnonymousProfile())
						? mapper.readValue(draft.getAnonymousProfile(), AnonymousProfileDTO.class)
						: null;
				for (UinBiometricDraft uinBiometricDraft : draft.getBiometrics()) {
					documents.add(new DocumentsDTO(uinBiometricDraft.getBiometricFileType(), CryptoUtil
							.encodeBase64(objectStoreHelper.getBiometricObject(uinHash, uinBiometricDraft.getBioFileId()))));
				}
				for (UinDocumentDraft uinDocumentDraft : draft.getDocuments()) {
					documents.add(new DocumentsDTO(uinDocumentDraft.getDoccatCode(), CryptoUtil
							.encodeBase64(objectStoreHelper.getDemographicObject(uinHash, uinDocumentDraft.getDocId()))));
				}
				return constructIdResponse(draft.getUinData(), draft.getStatusCode(), documents, anonymousProfile);
			} else {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GET_DRAFT,
						"DRAFT RECORD NOT FOUND");
				throw new IdRepoAppException(NO_RECORD_FOUND);
			}
		} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GET_DRAFT, e.getMessage());
			throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
		} catch (IOException e) {
			mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GET_DRAFT, "\n" + e.getMessage());
			throw new IdRepoAppException(UNKNOWN_ERROR, e);
		}
	}

	@Override
	public IdResponseDTO extractBiometrics(String registrationId, Map<String, String> extractionFormats)
			throws IdRepoAppException {
		if (!extractionFormats.isEmpty())
			try {
				Optional<UinDraft> draftOpt = uinDraftRepo.findByRegId(registrationId);
				if (draftOpt.isPresent()) {
					UinDraft draft = draftOpt.get();
					String uinHash = draft.getUinHash().split("_")[1];

					// Delete existing extracted data
					extractionFormats.entrySet()
							.forEach(extractionFormat -> draft.getBiometrics()
									.forEach(bio -> super.objectStoreHelper.deleteBiometricObject(uinHash,
											buildExtractionFileName(extractionFormat, bio.getBioFileId()))));

					// Extract new data
					for (UinBiometricDraft bioDraft : draft.getBiometrics()) {
						proxyService.getBiometricsForRequestedFormats(uinHash, bioDraft.getBioFileId(),
								extractionFormats,
								super.objectStoreHelper.getBiometricObject(uinHash, bioDraft.getBioFileId()));
					}
				} else {
					mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GET_DRAFT,
							"DRAFT RECORD NOT FOUND");
					throw new IdRepoAppException(NO_RECORD_FOUND);
				}
			} catch (DataAccessException | TransactionException | JDBCConnectionException e) {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GET_DRAFT,
						e.getMessage());
				throw new IdRepoAppException(DATABASE_ACCESS_ERROR);
			} catch (AmazonS3Exception e) {
				mosipLogger.error(IdRepoSecurityManager.getUser(), ID_REPO_DRAFT_SERVICE_IMPL, GET_DRAFT,
						e.getMessage());
				throw new IdRepoAppException(FILE_STORAGE_ACCESS_ERROR);
			}
		return constructIdResponse(null, DRAFTED, null, null);
	}

	private String buildExtractionFileName(Entry<String, String> extractionFormat, String bioFileId) {
		return bioFileId.split("\\.")[0].concat(DOT).concat(getModalityForFormat(extractionFormat.getKey())).concat(DOT)
				.concat(extractionFormat.getValue());
	}
	
	private String getModalityForFormat(String formatQueryParam) {
		return formatQueryParam.replace(EXTRACTION_FORMAT_QUERY_PARAM_SUFFIX, "");
	}

	private IdResponseDTO constructIdResponse(byte[] uinData, String status, List<DocumentsDTO> documents,
			AnonymousProfileDTO anonymousProfile) {
		IdResponseDTO idResponse = new IdResponseDTO();
		ResponseDTO response = new ResponseDTO();
		response.setStatus(status);
		response.setAnonymousProfile(anonymousProfile);
		if (Objects.nonNull(documents))
			response.setDocuments(documents);
		if (Objects.nonNull(uinData))
			response.setIdentity(convertToObject(uinData, Object.class));
		idResponse.setResponse(response);
		return idResponse;
	}

}
