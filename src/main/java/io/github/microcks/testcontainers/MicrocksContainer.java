/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.microcks.testcontainers;

import io.github.microcks.testcontainers.model.TestResult;
import io.github.microcks.testcontainers.model.TestRequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Testcontainers implementation for main Microcks container.
 * @author laurent
 */
public class MicrocksContainer extends GenericContainer<MicrocksContainer> {

   /** Get a SL4J logger. */
   private static final Logger log = LoggerFactory.getLogger(MicrocksContainer.class);

   private static final String MICROCKS_FULL_IMAGE_NAME = "quay.io/microcks/microcks-uber";
   private static final DockerImageName MICROCKS_IMAGE = DockerImageName.parse(MICROCKS_FULL_IMAGE_NAME);
   private static final String HTTP_UPLOAD_LINE_FEED = "\r\n";

   public static final int MICROCKS_HTTP_PORT = 8080;
   public static final int MICROCKS_GRPC_PORT = 9090;

   private static ObjectMapper mapper;

   /**
    * Build a new MicrocksContainer with its container image name as string. This image must
    * be compatible with quay.io/microcks/microcks-uber image.
    * @param image The name (with tag/version) of Microcks Uber distribution to use.
    */
   public MicrocksContainer(String image) {
      this(DockerImageName.parse(image));
   }

   /**
    * Build a new MicrocksContainer with its full container image name. This image must
    * be compatible with quay.io/microcks/microcks-uber image.
    * @param imageName The name (with tag/version) of Microcks Uber distribution to use.
    */
   public MicrocksContainer(DockerImageName imageName) {
      super(imageName);
      imageName.assertCompatibleWith(MICROCKS_IMAGE);

      withExposedPorts(MICROCKS_HTTP_PORT, MICROCKS_GRPC_PORT);

      waitingFor(Wait.forLogMessage(".*Started MicrocksApplication.*", 1));
   }

   /**
    * Get the Http endpoint where Microcks can be accessed (you'd have to append '/api' to access APIs)
    * @return The Http endpoint for talking to container.
    */
   public String getHttpEndpoint() {
      return String.format("http://%s:%s", getHost(), getMappedPort(MICROCKS_HTTP_PORT));
   }

   /**
    * Get the exposed mock endpoint for a SOAP Service.
    * @param service The name of Service/API
    * @param version The version of Service/API
    * @return A usable endpoint to interact with Microcks mocks.
    */
   public String getSoapMockEndpoint(String service, String version) {
      return String.format("%s/soap/%s/%s", getHttpEndpoint(),  service, version);
   }

   /**
    * Get the exposed mock endpoint for a REST API.
    * @param service The name of Service/API
    * @param version The version of Service/API
    * @return A usable endpoint to interact with Microcks mocks.
    */
   public String getRestMockEndpoint(String service, String version) {
      return String.format("%s/rest/%s/%s", getHttpEndpoint(),  service, version);
   }

   /**
    * Get the exposed mock endpoint for a GRPC Service.
    * @param service The name of Service/API
    * @param version The version of Service/API
    * @return A usable endpoint to interact with Microcks mocks.
    */
   public String getGraphQLMockEndpoint(String service, String version) {
      return String.format("%s/graphql/%s/%s", getHttpEndpoint(), service, version);
   }

   /**
    * Get the exposed mock endpoint for a GRPC Service.
    * @return A usable endpoint to interact with Microcks mocks.
    */
   public String getGrpcMockEndpoint() {
      return String.format("grpc://%s:%s", getHost(), getMappedPort(MICROCKS_GRPC_PORT));
   }

   /**
    * Import an artifact as a primary or main one within the Microcks container.
    * @param artifact The file representing artifact (OpenAPI, Postman collection, Protobuf, GraphQL schema, ...)
    * @throws IOException If file cannot be read of transmission exception occurs.
    * @throws InterruptedException If connection to the docker container is interrupted.
    * @throws MicrocksException If artifact cannot be correctly imported in container (probably malformed)
    */
   public void importAsMainArtifact(File artifact) throws IOException, InterruptedException, MicrocksException {
      importArtifact(artifact, true);
   }

   /**
    * Import an artifact as a secondary one within the Microcks container.
    * @param artifact The file representing artifact (OpenAPI, Postman collection, Protobuf, GraphQL schema, ...)
    * @throws IOException If file cannot be read of transmission exception occurs.
    * @throws InterruptedException If connection to the docker container is interrupted.
    * @throws MicrocksException If artifact cannot be correctly imported in container (probably malformed)
    */
   public void importAsSecondaryArtifact(File artifact) throws IOException, InterruptedException, MicrocksException {
      importArtifact(artifact, false);
   }

   /**
    * Launch a conformance test on an endpoint.
    * @param testRequest The test specifications (API under test, endpoint, runner, ...)
    * @return The final TestResult containing information on success/failure as well as details on test cases.
    * @throws IOException If connection to Microcks container failed (no route to host, low-level network stuffs)
    * @throws InterruptedException If connection to Microcks container is interrupted
    * @throws MicrocksException If Microcks fails creating a new test giving your request.
    */
   public TestResult testEndpoint(TestRequest testRequest) throws IOException, InterruptedException, MicrocksException {
      return testEndpoint(getHttpEndpoint(), testRequest);
   }

   /**
    * Launch a conformance test on an endpoint. This may be a fallback to non-static {@code testEndpoint(TestRequest testRequest)}
    * method if you don't have direct access to the MicrocksContainer instance you want to run this test on.
    * @param microcksContainerHttpEndpoint The Http endpoint where to reach running MicrocksContainer
    * @param testRequest The test specifications (API under test, endpoint, runner, ...)
    * @return The final TestResult containing information on success/failure as well as details on test cases.
    * @throws IOException If connection to Microcks container failed (no route to host, low-level network stuffs)
    * @throws MicrocksException If Microcks fails creating a new test giving your request.
    */
   public static TestResult testEndpoint(String microcksContainerHttpEndpoint, TestRequest testRequest) throws IOException, MicrocksException {
      String requestBody = getMapper().writeValueAsString(testRequest);

      // Build a new client on correct API endpoint.
      URL url = new URL(microcksContainerHttpEndpoint + "/api/tests");
      HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
      httpConn.setRequestMethod("POST");
      httpConn.setRequestProperty("Content-Type", "application/json");
      httpConn.setDoOutput(true);

      try (OutputStream os = httpConn.getOutputStream()) {
         byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
         os.write(input, 0, input.length);
      }

      StringBuilder responseContent = new StringBuilder();
      try (BufferedReader br = new BufferedReader(new InputStreamReader(httpConn.getInputStream(), StandardCharsets.UTF_8))) {
         String responseLine;
         while ((responseLine = br.readLine()) != null) {
            responseContent.append(responseLine.trim());
         }
      }

      if (httpConn.getResponseCode() == 201) {
         httpConn.disconnect();

         TestResult testResult = getMapper().readValue(responseContent.toString(), TestResult.class);
         log.debug("Got Test Result: {}, now polling for progression", testResult.getId());

         final String testResultId = testResult.getId();
         try {
            Awaitility.await()
                  .atMost(testRequest.getTimeout(), TimeUnit.MILLISECONDS)
                  .pollDelay(100, TimeUnit.MILLISECONDS)
                  .pollInterval(200, TimeUnit.MILLISECONDS)
                  .until(() -> !refreshTestResult(microcksContainerHttpEndpoint, testResultId).isInProgress());
         } catch (ConditionTimeoutException timeoutException) {
            log.info("Caught a ConditionTimeoutException for test on {}", testRequest.getTestEndpoint());
         }

         // Return the final result.
         return refreshTestResult(microcksContainerHttpEndpoint, testResultId);
      }
      if (log.isErrorEnabled()) {
         log.error("Couldn't launch on new test on Microcks with status {} ", httpConn.getResponseCode());
         log.error("Error response body is {}", responseContent);
      }
      httpConn.disconnect();
      throw new MicrocksException("Couldn't launch on new test on Microcks. Please check Microcks container logs");
   }

   private void importArtifact(File artifact, boolean mainArtifact) throws IOException, MicrocksException {
      if (!artifact.exists()) {
         throw new IOException("Artifact " + artifact.getPath() + " does not exist or can't be read.");
      }

      // Creates a unique boundary based on time stamp
      String boundary = "===" + System.currentTimeMillis() + "===";

      URL url = new URL(getHttpEndpoint() + "/api/artifact/upload" + (mainArtifact ? "" : "?mainArtifact=false"));
      HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
      httpConn.setUseCaches(false);
      httpConn.setDoOutput(true);
      httpConn.setDoInput(true);
      httpConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

      try (OutputStream os = httpConn.getOutputStream();
           PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);
           FileInputStream is = new FileInputStream(artifact)) {

         writer.append("--" + boundary)
               .append(HTTP_UPLOAD_LINE_FEED)
               .append("Content-Disposition: form-data; name=\"file\"; filename=\"" + artifact.getName() + "\"")
               .append(HTTP_UPLOAD_LINE_FEED)
               .append("Content-Type: application/octet-stream")
               .append(HTTP_UPLOAD_LINE_FEED)
               .append("Content-Transfer-Encoding: binary")
               .append(HTTP_UPLOAD_LINE_FEED)
               .append(HTTP_UPLOAD_LINE_FEED);
         writer.flush();

         byte[] buffer = new byte[4096];
         int bytesRead = -1;
         while ((bytesRead = is.read(buffer)) != -1) {
            os.write(buffer, 0, bytesRead);
         }
         os.flush();

         // Finalize writer with a boundary before flushing.
         writer.append(HTTP_UPLOAD_LINE_FEED)
               .append("--" + boundary + "--")
               .append(HTTP_UPLOAD_LINE_FEED).flush();
      }

      if (httpConn.getResponseCode() != 201) {
         // Read response content for diagnostic purpose.
         StringBuilder responseContent = new StringBuilder();
         try (BufferedReader br = new BufferedReader(new InputStreamReader(httpConn.getInputStream(), StandardCharsets.UTF_8))) {
            String responseLine = null;
            while ((responseLine = br.readLine()) != null) {
               responseContent.append(responseLine.trim());
            }
         }
         // Disconnect Http connection.
         httpConn.disconnect();

         log.error("Artifact has not been correctly been imported: {}", responseContent);
         throw new MicrocksException("Artifact has not been correctly been imported: " + responseContent);
      }
      // Disconnect Http connection.
      httpConn.disconnect();
   }

   private static ObjectMapper getMapper() {
      if (mapper == null) {
         mapper = new ObjectMapper();
         // Do not include null values in both serialization and deserialization.
         mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
         mapper.setPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
      }
      return mapper;
   }

   private static TestResult refreshTestResult(String microcksContainerHttpEndpoint, String testResultId) throws IOException {
      // Build a new client on correct API endpoint.
      URL url = new URL(microcksContainerHttpEndpoint + "/api/tests/" + testResultId);
      HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
      httpConn.setRequestMethod("GET");
      httpConn.setRequestProperty("Accept", "application/json");
      httpConn.setDoOutput(false);

      // Send the request and parse response body.
      StringBuilder content = new StringBuilder();
      try (BufferedReader br = new BufferedReader(new InputStreamReader(httpConn.getInputStream()))) {
         String inputLine;
         while ((inputLine = br.readLine()) != null) {
            content.append(inputLine);
         }
      }
      // Disconnect Http connection.
      httpConn.disconnect();

      return getMapper().readValue(content.toString(), TestResult.class);
   }
}
