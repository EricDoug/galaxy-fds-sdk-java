package com.xiaomi.infra.galaxy.fds.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.google.common.base.Preconditions;
import com.google.common.collect.LinkedListMultimap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.glassfish.jersey.client.ClientConfig;

import com.xiaomi.infra.galaxy.exception.GalaxyException;
import com.xiaomi.infra.galaxy.fds.Action;
import com.xiaomi.infra.galaxy.fds.Constants;
import com.xiaomi.infra.galaxy.fds.SubResource;
import com.xiaomi.infra.galaxy.fds.auth.Common;
import com.xiaomi.infra.galaxy.fds.auth.HttpMethod;
import com.xiaomi.infra.galaxy.fds.auth.SignAlgorithm;
import com.xiaomi.infra.galaxy.fds.auth.XiaomiHeader;
import com.xiaomi.infra.galaxy.fds.auth.signature.Signer;
import com.xiaomi.infra.galaxy.fds.bean.BucketBean;
import com.xiaomi.infra.galaxy.fds.bean.GrantBean;
import com.xiaomi.infra.galaxy.fds.bean.GranteeBean;
import com.xiaomi.infra.galaxy.fds.bean.ObjectBean;
import com.xiaomi.infra.galaxy.fds.bean.OwnerBean;
import com.xiaomi.infra.galaxy.fds.client.credential.GalaxyFDSCredential;
import com.xiaomi.infra.galaxy.fds.client.filter.MetricsResponseFilter;
import com.xiaomi.infra.galaxy.fds.client.metrics.MetricsCollector;
import com.xiaomi.infra.galaxy.fds.client.filter.MetricsRequestFilter;
import com.xiaomi.infra.galaxy.fds.client.model.FDSBucket;
import com.xiaomi.infra.galaxy.fds.client.model.FDSMd5InputStream;
import com.xiaomi.infra.galaxy.fds.client.model.FDSObject;
import com.xiaomi.infra.galaxy.fds.client.model.FDSObjectInputStream;
import com.xiaomi.infra.galaxy.fds.client.model.FDSObjectListing;
import com.xiaomi.infra.galaxy.fds.client.model.FDSObjectSummary;
import com.xiaomi.infra.galaxy.fds.client.model.Owner;
import com.xiaomi.infra.galaxy.fds.exception.GalaxyFDSClientException;
import com.xiaomi.infra.galaxy.fds.filter.FDSClientLogFilter;
import com.xiaomi.infra.galaxy.fds.model.AccessControlList;
import com.xiaomi.infra.galaxy.fds.model.AccessControlList.Grant;
import com.xiaomi.infra.galaxy.fds.model.AccessControlList.GrantType;
import com.xiaomi.infra.galaxy.fds.model.AccessControlList.Permission;
import com.xiaomi.infra.galaxy.fds.model.AccessControlList.UserGroups;
import com.xiaomi.infra.galaxy.fds.model.ClientMetrics;
import com.xiaomi.infra.galaxy.fds.model.FDSObjectMetadata;
import com.xiaomi.infra.galaxy.fds.model.FDSObjectMetadata.PredefinedMetadata;
import com.xiaomi.infra.galaxy.fds.result.AccessControlPolicy;
import com.xiaomi.infra.galaxy.fds.result.ListAllBucketsResult;
import com.xiaomi.infra.galaxy.fds.result.ListDomainMappingsResult;
import com.xiaomi.infra.galaxy.fds.result.ListObjectsResult;
import com.xiaomi.infra.galaxy.fds.result.PutObjectResult;
import com.xiaomi.infra.galaxy.fds.result.QuotaPolicy;

public class GalaxyFDSClient implements GalaxyFDS {

  private final GalaxyFDSCredential credential;
  private final FDSClientConfiguration fdsConfig;
  private ClientConfig clientConfig;
  private Client client;
  private MetricsCollector metricsCollector;
  private String delimiter = "/";
  private final Random random = new Random();
  private final String clientId = UUID.randomUUID().toString().substring(0, 8);

  public static final String GALAXY_FDS_SERVER_BASE_URI_KEY =
      "galaxy.fds.server.base.uri";

  // TODO(wuzesheng) Make the authenticator configurable and let the
  // authenticator supply sign algorithm and generate signature
  private static SignAlgorithm SIGN_ALGORITHM = SignAlgorithm.HmacSHA1;

  public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
      "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);

  static {
    DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
  }

  private static final Log LOG = LogFactory.getLog(GalaxyFDSClient.class);

  public GalaxyFDSClient(GalaxyFDSCredential credential,
      FDSClientConfiguration fdsConfig) {
    this.credential = credential;
    this.fdsConfig = fdsConfig;

    init();
  }

  private void init() {
    clientConfig = new ClientConfig();
    clientConfig.register(new FDSClientLogFilter());
    client = ClientBuilder.newClient(clientConfig);
    if (fdsConfig.isMetricsEnabled()) {
      metricsCollector = new MetricsCollector(this);
      client.register(MetricsRequestFilter.class);
      client.register(MetricsResponseFilter.class);
    }
  }

  public void setDelimiter(String delimiter) {
    this.delimiter = delimiter;
  }

  @Override
  public List<FDSBucket> listBuckets() throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), "", (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.ListBuckets)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      List<FDSBucket> buckets = null;
      ListAllBucketsResult result = response.readEntity(
          ListAllBucketsResult.class);
      if (result != null) {
        OwnerBean owner = result.getOwner();
        buckets = new ArrayList<FDSBucket>(result.getBuckets().size());
        for (BucketBean b : result.getBuckets()) {
          FDSBucket bucket = new FDSBucket(b.getName());
          bucket.setOwner(new Owner(owner.getId(), owner.getDisplayName()));
          buckets.add(bucket);
        }
      }
      return buckets;
    } else {
      String errorMsg = "List bucket for current user failed, status="
          + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void createBucket(String bucketName) throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, MediaType.APPLICATION_JSON, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .buildPut(Entity.entity("{}", MediaType.APPLICATION_JSON_TYPE))
        .property(Common.ACTION, Action.PutBucket)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Create bucket failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void deleteBucket(String bucketName) throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.DELETE, null, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .buildDelete()
        .property(Common.ACTION, Action.DeleteBucket)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Delete bucket failed, status=" + response.getStatus() +
          ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void getBucket(String bucketName) throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.GetBucketMeta)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Status.OK.getStatusCode()) {
      String errorMsg = "Get bucket failed, status=" + response.getStatus() +
          ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public boolean doesBucketExist(String bucketName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.HEAD, null, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .build(HttpMethod.HEAD.toString())
        .property(Common.ACTION, Action.HeadBucket)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    int status = response.getStatus();
    if (status == Response.Status.OK.getStatusCode()) {
      return true;
    } else if (status == Response.Status.NOT_FOUND.getStatusCode()) {
      return false;
    } else {
      String errorMsg = "Check bucket existence failed, status=" + status +
          ", reason=" + Status.fromStatusCode(status).getReasonPhrase();
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public AccessControlList getBucketAcl(String bucketName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, SubResource.ACL);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.GetBucketACL)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      AccessControlPolicy acp = response.readEntity(AccessControlPolicy.class);
      return acpToAcl(acp);
    } else {
      String errorMsg = "Get acl for bucket " + bucketName
          + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void setBucketAcl(String bucketName, AccessControlList acl)
      throws GalaxyFDSClientException {
    Preconditions.checkNotNull(acl);
    AccessControlPolicy acp = aclToAcp(acl);

    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, SubResource.ACL);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, MediaType.APPLICATION_JSON, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildPut(Entity.entity(acp, MediaType.APPLICATION_JSON_TYPE))
        .property(Common.ACTION, Action.PutBucketACL)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Set acl for bucket " + bucketName + " failed"
          + ", status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public QuotaPolicy getBucketQuota(String bucketName)
      throws GalaxyFDSClientException {
    Preconditions.checkNotNull(bucketName);
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, SubResource.QUOTA);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);
    Response response = client.target(uri).request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.GetBucketQuota)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      QuotaPolicy quotaPolicy = response.readEntity(QuotaPolicy.class);
      return quotaPolicy;
    } else {
      String errorMsg = "Get quota for bucket " + bucketName + " failed," +
          " status=" + response.getStatus() + ", reason="
          + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void setBucketQuota(String bucketName, QuotaPolicy quotaPolicy)
      throws GalaxyFDSClientException {
    Preconditions.checkNotNull(quotaPolicy);
    Preconditions.checkNotNull(bucketName);

    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, SubResource.QUOTA);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, MediaType.APPLICATION_JSON, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildPut(Entity.entity(quotaPolicy, MediaType.APPLICATION_JSON_TYPE))
        .property(Common.ACTION, Action.PutBucketQuota)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Set quota for bucket " + bucketName + " failed"
          + ", status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public FDSObjectListing listObjects(String bucketName)
      throws GalaxyFDSClientException {
    return listObjects(bucketName, "", this.delimiter);
  }

  @Override
  public FDSObjectListing listObjects(String bucketName, String prefix)
      throws GalaxyFDSClientException {
    return listObjects(bucketName, prefix, this.delimiter);
  }

  @Override
  public FDSObjectListing listObjects(String bucketName, String prefix,
      String delimiter) throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.GET, null, null);

    Response response = client.target(uri.toString())
        .queryParam("prefix", prefix)
        .queryParam("delimiter", delimiter)
        .request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.ListObjects)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      ListObjectsResult result = response.readEntity(ListObjectsResult.class);
      return getObjectListing(result);
    } else {
      String errorMsg = "List objects under bucket " + bucketName
          + " with prefix " + prefix + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public FDSObjectListing listTrashObjects(String prefix, String delimiter)
      throws GalaxyFDSClientException {
    return listObjects(Constants.TRASH_BUCKET_NAME, prefix, delimiter);
  }

  @Override
  public FDSObjectListing listNextBatchOfObjects(
      FDSObjectListing previousObjectListing) throws GalaxyFDSClientException {
    if (!previousObjectListing.isTruncated()) {
      LOG.warn("The previous listObjects() response is complete, " +
          "call of listNextBatchOfObjects() will be ingored");
      return null;
    }

    String bucketName = previousObjectListing.getBucketName();
    String prefix = previousObjectListing.getPrefix();
    String delimiter = previousObjectListing.getDelimiter();
    String marker = previousObjectListing.getNextMarker();
    int maxKeys = previousObjectListing.getMaxKeys();

    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);

    Response response = client.target(uri.toString())
        .queryParam("prefix", prefix)
        .queryParam("delimiter", delimiter)
        .queryParam("marker", marker)
        .queryParam("maxKeys", maxKeys)
        .request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.ListObjects)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      ListObjectsResult result = response.readEntity(ListObjectsResult.class);
      return getObjectListing(result);
    } else {
      String errorMsg = "List next batch of objects under bucket " + bucketName
          + " with prefix " + prefix + " and marker " + marker
          + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public PutObjectResult putObject(String bucketName, String objectName,
      File file) throws GalaxyFDSClientException {
    FileInputStream stream = null;
    try {
      stream = new FileInputStream(file);
      return putObject(bucketName, objectName, stream, null);
    } catch (FileNotFoundException e) {
      String errorMsg = "File not found, file=" + file.getName();
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg, e);
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException e) {
          String errorMsg = "Close file input stream failed";
          LOG.error(errorMsg);
          throw new GalaxyFDSClientException(errorMsg, e);
        }
      }
    }
  }

  @Override
  public PutObjectResult putObject(String bucketName, String objectName,
      InputStream input, FDSObjectMetadata metadata)
      throws GalaxyFDSClientException {
    String mediaType = MediaType.APPLICATION_OCTET_STREAM;
    if (metadata != null && metadata.getContentType() != null) {
      mediaType = metadata.getContentType();
    }

    if (fdsConfig.isMd5CalculateEnabled()) {
      if (metadata == null) {
        metadata = new FDSObjectMetadata();
      }
      metadata.addHeader(XiaomiHeader.MD5_ATTACHED_STREAM.getName(), "1");
      try {
        input = new FDSMd5InputStream(input);
      } catch (NoSuchAlgorithmException e) {
        throw new GalaxyFDSClientException("Cannot init md5", e);
      }
    }

    URI uri = formatUri(fdsConfig.getUploadBaseUri(), bucketName + "/"
        + objectName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, mediaType, metadata);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildPut(Entity.entity(input, mediaType))
        .property(Common.ACTION, Action.PutObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      PutObjectResult result = response.readEntity(PutObjectResult.class);
      return result;
    } else {
      String errorMsg = "Upload object " + objectName + " to bucket "
          + bucketName + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public PutObjectResult postObject(String bucketName, File file)
      throws GalaxyFDSClientException {
    FileInputStream stream = null;
    try {
      stream = new FileInputStream(file);
      return postObject(bucketName, stream, null);
    } catch (FileNotFoundException e) {
      String errorMsg = "File not found, file=" + file.getName();
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg, e);
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException e) {
          String errorMsg = "Close file input stream failed";
          LOG.error(errorMsg);
          throw new GalaxyFDSClientException(errorMsg, e);
        }
      }
    }
  }

  @Override
  public PutObjectResult postObject(String bucketName, InputStream input,
      FDSObjectMetadata metadata) throws GalaxyFDSClientException {
    String mediaType = MediaType.APPLICATION_OCTET_STREAM;
    if (metadata != null && metadata.getContentType() != null) {
      mediaType = metadata.getContentType();
    }

    if (fdsConfig.isMd5CalculateEnabled()) {
      if (metadata == null) {
        metadata = new FDSObjectMetadata();
      }
      metadata.addHeader(XiaomiHeader.MD5_ATTACHED_STREAM.getName(), "1");
      try {
        input = new FDSMd5InputStream(input);
      } catch (NoSuchAlgorithmException e) {
        throw new GalaxyFDSClientException("Cannot init md5", e);
      }
    }

    URI uri = formatUri(fdsConfig.getUploadBaseUri(), bucketName + "/",
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.POST, mediaType, metadata);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildPost(Entity.entity(input, mediaType))
        .property(Common.ACTION, Action.PostObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      PutObjectResult result = response.readEntity(PutObjectResult.class);
      return result;
    } else {
      String errorMsg = "Post object to bucket " + bucketName +
          " failed, status=" + response.getStatus() + ", reason=" +
          response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public FDSObject getObject(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    // start from position 0 by default
    return getObject(bucketName, objectName, 0);
  }

  @Override
  public FDSObject getObject(String bucketName, String objectName, long pos)
      throws GalaxyFDSClientException {
    if (pos < 0) {
      String errorMsg = "Get object " + objectName + " from bucket "
          + bucketName + " failed, reason=invalid seek position:" + pos;
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }

    URI uri = formatUri(fdsConfig.getDownloadBaseUri(), bucketName + "/"
        + objectName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);
    if (pos > 0) {
      headers.putSingle(Common.RANGE, "bytes=" + pos + "-");
    }

    Response response = client.target(uri)
        .request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.GetObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Status.OK.getStatusCode() ||
        response.getStatus() == Status.PARTIAL_CONTENT.getStatusCode()) {
      FDSObject object = new FDSObject();
      FDSObjectInputStream stream = new FDSObjectInputStream(response);
      object.setObjectContent(stream);

      FDSObjectSummary summary = new FDSObjectSummary();
      summary.setBucketName(bucketName);
      summary.setObjectName(objectName);
      summary.setSize(response.getLength());
      object.setObjectSummary(summary);

      object.setObjectMetadata(parseObjectMetadataFromHeaders(
          response.getHeaders()));
      return object;
    } else {
      String errorMsg = "Get object " + objectName + " from bucket "
          + bucketName + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public FDSObjectMetadata getObjectMetadata(String bucketName,
      String objectName) throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        SubResource.METADATA);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.GET, null, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.GetObjectMetadata)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      FDSObjectMetadata metadata = parseObjectMetadataFromHeaders(
          response.getHeaders());
      return metadata;
    } else {
      String errorMsg = "Get metadata for object " + objectName +
          " under bucket " + bucketName + " failed, status=" +
          response.getStatus() + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public AccessControlList getObjectAcl(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        SubResource.ACL);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.GET, null, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.GetObjectACL)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Response.Status.OK.getStatusCode()) {
      AccessControlPolicy acp = response.readEntity(AccessControlPolicy.class);
      return acpToAcl(acp);
    } else {
      String errorMsg = "Get acl for object " + objectName + " under bucket "
          + bucketName + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void setObjectAcl(String bucketName, String objectName,
      AccessControlList acl) throws GalaxyFDSClientException {
    Preconditions.checkNotNull(acl);
    AccessControlPolicy acp = aclToAcp(acl);

    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        SubResource.ACL);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, MediaType.APPLICATION_JSON, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildPut(Entity.entity(acp, MediaType.APPLICATION_JSON_TYPE))
        .property(Common.ACTION, Action.PutObjectACL)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Set acl for object " + objectName + " under bucket "
          + bucketName + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public boolean doesObjectExist(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.HEAD, null, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .build(HttpMethod.HEAD.toString())
        .property(Common.ACTION, Action.HeadObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    int status = response.getStatus();
    if (status == Response.Status.OK.getStatusCode()) {
      return true;
    } else if (status == Response.Status.NOT_FOUND.getStatusCode()) {
      return false;
    } else {
      String errorMsg = "Check existence of object " + objectName
          + " under bucket " + bucketName + " failed, status=" + status
          + ", reason=" + Status.fromStatusCode(status).getReasonPhrase();
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void deleteObject(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.DELETE, null, null);

    Response response = client.target(uri).request()
        .headers(headers)
        .buildDelete()
        .property(Common.ACTION, Action.DeleteObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Delete object " + objectName + " under bucket "
          + bucketName + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void restoreObject(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, MediaType.APPLICATION_JSON, null);

    Response response = client.target(uri)
        .queryParam("restore", "")
        .request()
        .headers(headers)
        .buildPut(Entity.entity("", MediaType.APPLICATION_JSON_TYPE))
        .property(Common.ACTION, Action.RestoreObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Restore object " + objectName + " under bucket "
          + bucketName + " failed, status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void renameObject(String bucketName, String srcObjectName,
      String dstObjectName) throws GalaxyFDSClientException {
    MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + srcObjectName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(
        uri, HttpMethod.PUT, mediaType.toString(), null);

    Response response = client.target(uri.toString())
        .queryParam("renameTo", dstObjectName)
        .request().headers(headers)
        .buildPut(Entity.entity("", mediaType))
        .property(Common.ACTION, Action.RenameObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Rename object " + srcObjectName + " to object "
          + dstObjectName + " under bucket " + bucketName + " failed, status="
          + response.getStatus() + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void prefetchObject(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    MediaType mediaType = MediaType.APPLICATION_JSON_TYPE;
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.PUT, mediaType.toString(), null);

    Response response = client.target(uri.toString())
        .queryParam("prefetch", "")
        .request().headers(headers)
        .buildPut(Entity.entity("", mediaType))
        .property(Common.ACTION, Action.PrefetchObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Status.OK.getStatusCode()) {
      String errorMsg = "Prefetch object " + objectName + " under bucket " +
          bucketName + " failed, status=" + response.getStatus() + ", reason=" +
          response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void refreshObject(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    MediaType mediaType = MediaType.APPLICATION_JSON_TYPE;
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName + "/" + objectName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.PUT, mediaType.toString(), null);

    Response response = client.target(uri.toString())
        .queryParam("refresh", "")
        .request().headers(headers)
        .buildPut(Entity.entity("", mediaType))
        .property(Common.ACTION, Action.RefreshObject)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Status.OK.getStatusCode()) {
      String errorMsg = "Refresh object " + objectName + " under bucket " +
          bucketName + " failed, status=" + response.getStatus() + ", reason=" +
          response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void putDomainMapping(String bucketName, String domainName)
      throws GalaxyFDSClientException {
    MediaType mediaType = MediaType.APPLICATION_JSON_TYPE;
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName,
        (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.PUT, mediaType.toString(), null);

    Response response = client.target(uri.toString())
        .queryParam("domain", domainName)
        .request().headers(headers)
        .buildPut(Entity.entity("", mediaType))
        .property(Common.ACTION, Action.PutDomainMapping)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Status.OK.getStatusCode()) {
      String errorMsg = "Add domain mapping failed, bucketName=" + bucketName
          + ", domainName=" + domainName + ", status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public List<String> listDomainMappings(String bucketName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.GET, null, null);

    Response response = client.target(uri.toString())
        .queryParam("domain", "")
        .request().headers(headers)
        .buildGet()
        .property(Common.ACTION, Action.ListDomainMappings)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() == Status.OK.getStatusCode()) {
      ListDomainMappingsResult listDomainMappingsResult = response.readEntity(
          ListDomainMappingsResult.class);
      return listDomainMappingsResult.getDomainMappings();
    } else {
      String errorMsg = "List domain mappings failed, bucketName=" + bucketName
          + ", status=" + response.getStatus() + ", reason="
          + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void deleteDomainMapping(String bucketName, String domainName)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), bucketName, (SubResource[]) null);
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.DELETE, null, null);

    Response response = client.target(uri.toString())
        .queryParam("domain", domainName)
        .request().headers(headers)
        .buildDelete()
        .property(Common.ACTION, Action.DeleteDomainMapping)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .invoke();

    if (response.getStatus() != Status.OK.getStatusCode()) {
      String errorMsg = "Delete domain mapping failed, bucketName=" + bucketName
          + ", domainName=" + domainName + ", status=" + response.getStatus()
          + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  @Override
  public void setPublic(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    setPublic(bucketName, objectName, false);
  }

  @Override
  public void setPublic(String bucketName, String objectName,
      boolean disablePrefetch) throws GalaxyFDSClientException {
    AccessControlList acl = new AccessControlList();
    acl.addGrant(new Grant(UserGroups.ALL_USERS.name(), Permission.READ,
        GrantType.GROUP));
    setObjectAcl(bucketName, objectName, acl);
    if (!disablePrefetch) {
      prefetchObject(bucketName, objectName);
    }
  }

  @Override
  public URI generateDownloadObjectUri(String bucketName, String objectName)
      throws GalaxyFDSClientException {
    return formatUri(fdsConfig.getDownloadBaseUri(), bucketName + "/"
        + objectName, (SubResource[]) null);
  }

  @Override
  public URI generatePresignedUri(String bucketName, String objectName,
      Date expiration) throws GalaxyFDSClientException {
    return generatePresignedUri(bucketName, objectName,
        expiration, HttpMethod.GET);
  }

  @Override
  public URI generatePresignedCdnUri(String bucketName, String objectName,
      Date expiration) throws GalaxyFDSClientException {
    return generatePresignedCdnUri(bucketName, objectName,
        expiration, HttpMethod.GET);
  }

  @Override
  public URI generatePresignedUri(String bucketName, String objectName,
      Date expiration, HttpMethod httpMethod) throws GalaxyFDSClientException {
    try {
      return Signer.generatePresignedUri(fdsConfig.getBaseUri(), bucketName, objectName,
          null, expiration, httpMethod, credential.getGalaxyAccessId(),
          credential.getGalaxyAccessSecret(), SIGN_ALGORITHM);
    } catch (GalaxyException e) {
      throw new GalaxyFDSClientException(e);
    }
  }

  @Override
  public URI generatePresignedCdnUri(String bucketName, String objectName,
      Date expiration, HttpMethod httpMethod) throws GalaxyFDSClientException {
    try {
      return Signer.generatePresignedUri(fdsConfig.getCdnBaseUri(), bucketName,
          objectName, null, expiration, httpMethod, credential.getGalaxyAccessId(),
          credential.getGalaxyAccessSecret(), SIGN_ALGORITHM);
    } catch (GalaxyException e) {
      throw new GalaxyFDSClientException(e);
    }
  }

  @Override
  public URI generatePresignedUri(String bucketName, String objectName,
      SubResource subResource, Date expiration, HttpMethod httpMethod)
      throws GalaxyFDSClientException {
    List<String> subResources = new ArrayList<String>();
    subResources.add(subResource.getName());
    return generatePresignedUri(bucketName, objectName, subResources,
        expiration, httpMethod);
  }

  @Override
  public URI generatePresignedUri(String bucketName, String objectName,
      List<String> subResources, Date expiration, HttpMethod httpMethod)
      throws GalaxyFDSClientException {
    try {
      return Signer.generatePresignedUri(fdsConfig.getBaseUri(), bucketName, objectName,
          subResources, expiration, httpMethod, credential.getGalaxyAccessId(),
          credential.getGalaxyAccessSecret(), SIGN_ALGORITHM);
    } catch (GalaxyException e) {
      throw new GalaxyFDSClientException(e);
    }
  }

  @Override
  public URI generatePresignedCdnUri(String bucketName, String objectName,
      SubResource subResource, Date expiration, HttpMethod httpMethod)
      throws GalaxyFDSClientException {
    List<String> subResources = new ArrayList<String>();
    subResources.add(subResource.getName());
    return generatePresignedCdnUri(bucketName, objectName, subResources,
        expiration, httpMethod);
  }

  @Override
  public URI generatePresignedCdnUri(String bucketName, String objectName,
      List<String> subResources, Date expiration, HttpMethod httpMethod)
      throws GalaxyFDSClientException {
    try {
      return Signer.generatePresignedUri(fdsConfig.getCdnBaseUri(), bucketName,
          objectName, subResources, expiration, httpMethod,
          credential.getGalaxyAccessId(), credential.getGalaxyAccessSecret(),
          SIGN_ALGORITHM);
    } catch (GalaxyException e) {
      throw new GalaxyFDSClientException(e);
    }
  }

  /**
   * Put client metrics to server. This method should only be used internally.
   *
   * @param clientMetrics Metrics to be pushed to server.
   * @throws GalaxyFDSClientException
   */
  public void putClientMetrics(ClientMetrics clientMetrics)
      throws GalaxyFDSClientException {
    URI uri = formatUri(fdsConfig.getBaseUri(), "", (SubResource[]) null);
    MediaType mediaType = MediaType.APPLICATION_JSON_TYPE;
    MultivaluedMap<String, Object> headers = prepareRequestHeader(uri,
        HttpMethod.PUT, mediaType.toString(), null);

    Response response = client.target(uri)
        .queryParam("clientMetrics", "")
        .request()
        .headers(headers)
        .property(Common.ACTION, Action.PutClientMetrics)
        .property(Common.METRICS_COLLECTOR, metricsCollector)
        .put(Entity.entity(clientMetrics, mediaType));

    if (response.getStatus() != Response.Status.OK.getStatusCode()) {
      String errorMsg = "Put client metrics failed, status="
          + response.getStatus() + ", reason=" + response.readEntity(String.class);
      LOG.error(errorMsg);
      throw new GalaxyFDSClientException(errorMsg);
    }
  }

  URI formatUri(String baseUri,
      String resource, SubResource... subResourceParams)
      throws GalaxyFDSClientException {
    String subResource = null;
    if (subResourceParams != null) {
      for (SubResource param : subResourceParams) {
        if (subResource != null) {
          subResource += "&" + param.getName();
        } else {
          subResource = param.getName();
        }
      }
    }

    try {
      URI uri = new URI(baseUri);
      String schema = uri.getScheme();
      String host = uri.getHost();
      int port = uri.getPort();
      URI encodedUri;
      if (subResource == null) {
        encodedUri = new URI(schema, null, host, port, "/" + resource, null, null);
      } else {
        encodedUri = new URI(schema, null, host, port, "/" + resource, subResource, null);
      }
      return encodedUri;
    } catch (URISyntaxException e) {
      LOG.error("Invalid uri syntax", e);
      throw new GalaxyFDSClientException("Invalid uri syntax", e);
    }
  }

  MultivaluedMap<String, Object> prepareRequestHeader(URI uri,
      HttpMethod method, String mediaType, FDSObjectMetadata metadata)
      throws GalaxyFDSClientException {
    LinkedListMultimap<String, String> headers = LinkedListMultimap.create();

    if (metadata != null) {
      for (Map.Entry<String, String> e : metadata.getRawMetadata().entrySet()) {
        headers.put(e.getKey(), e.getValue());
      }
    }

    // Format date
    String date = DATE_FORMAT.format(new Date());
    headers.put(Common.DATE, date);

    // Set content type
    if (mediaType != null) {
      headers.put(Common.CONTENT_TYPE, mediaType);
    }

    // Set unique request id
    headers.put(XiaomiHeader.REQUEST_ID.getName(), getUniqueRequestId());

    // Set authorization information
    byte[] signature;
    try {
      URI relativeUri = new URI(uri.toString().substring(
          uri.toString().indexOf('/', uri.toString().indexOf(':') + 3)));
      signature = Signer.signToBase64(method, relativeUri, headers,
          credential.getGalaxyAccessSecret(), SIGN_ALGORITHM);
    } catch (InvalidKeyException e) {
      LOG.error("Invalid secret key spec", e);
      throw new GalaxyFDSClientException("Invalid secret key sepc", e);
    } catch (NoSuchAlgorithmException e) {
      LOG.error("Unsupported signature algorithm:" + SIGN_ALGORITHM, e);
      throw new GalaxyFDSClientException("Unsupported signature slgorithm:"
          + SIGN_ALGORITHM, e);
    } catch (Exception e) {
      throw new GalaxyFDSClientException(e);
    }
    String authString = "Galaxy-V2 " + credential.getGalaxyAccessId() + ":"
        + new String(signature);
    headers.put(Common.AUTHORIZATION, authString);

    MultivaluedMap<String, Object> httpHeaders =
        new MultivaluedHashMap<String, Object>();
    for (Entry<String, String> entry : headers.entries()) {
      httpHeaders.putSingle(entry.getKey(), entry.getValue());
    }
    return httpHeaders;
  }

  AccessControlList acpToAcl(AccessControlPolicy acp) {
    AccessControlList acl = null;
    if (acp != null) {
      acl = new AccessControlList();
      for (GrantBean g : acp.getAccessControlList()) {
        acl.addGrant(new Grant(g.getGrantee().getId(),
            g.getPermission(), g.getType()));
      }
    }
    return acl;
  }

  AccessControlPolicy aclToAcp(AccessControlList acl) {
    AccessControlPolicy acp = null;
    if (acl != null) {
      acp = new AccessControlPolicy();
      acp.setOwner(new OwnerBean(credential.getGalaxyAccessId()));
      List<GrantBean> grants = new ArrayList<GrantBean>(
          acl.getGrantList().size());
      for (Grant g : acl.getGrantList()) {
        grants.add(new GrantBean(new GranteeBean(g.getGranteeId()),
            g.getPermission(), g.getType()));
      }
      acp.setAccessControlList(grants);
    }
    return acp;
  }

  FDSObjectListing getObjectListing(ListObjectsResult result) {
    FDSObjectListing listing = null;
    if (result != null) {
      listing = new FDSObjectListing();
      listing.setBucketName(result.getName());
      listing.setPrefix(result.getPrefix());
      listing.setDelimiter(result.getDelimiter());
      listing.setMarker(result.getMarker());
      listing.setNextMarker(result.getNextMarker());
      listing.setMaxKeys(result.getMaxKeys());
      listing.setTruncated(result.isTruncated());

      List<FDSObjectSummary> summaries = new ArrayList<FDSObjectSummary>(
          result.getObjects().size());
      for (ObjectBean o : result.getObjects()) {
        FDSObjectSummary summary = new FDSObjectSummary();
        summary.setBucketName(result.getName());
        summary.setObjectName(o.getName());
        summary.setSize(o.getSize());
        summary.setOwner(new Owner(o.getOwner().getId(),
            o.getOwner().getDisplayName()));
        summaries.add(summary);
      }
      listing.setObjectSummaries(summaries);
      listing.setCommonPrefixes(result.getCommonPrefixes());
    }
    return listing;
  }

  public void setClient(Client client) {
    this.client = client;
  }

  private String getUniqueRequestId() {
    return clientId + "_" + random.nextInt();
  }

  private FDSObjectMetadata parseObjectMetadataFromHeaders(
      MultivaluedMap<String, Object> headers) {
    FDSObjectMetadata metadata = new FDSObjectMetadata();

    for (PredefinedMetadata m : PredefinedMetadata.values()) {
      String value = (String) headers.getFirst(m.getHeader());
      if (value != null && !value.isEmpty()) {
        metadata.addHeader(m.getHeader(), value);
      }
    }

    for (Map.Entry<String, List<Object>> e : headers.entrySet()) {
      if (e.getKey().startsWith(FDSObjectMetadata.USER_DEFINED_META_PREFIX)) {
        metadata.addHeader(e.getKey(), (String) e.getValue().get(0));
      }
    }
    return metadata;
  }
}
