/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.amber.core.storage.model

import com.typesafe.scalalogging.LazyLogging
import org.apache.texera.common.config.EnvironmentalVariable
import org.apache.texera.amber.core.storage.ResourceType
import org.apache.texera.amber.core.storage.UserTokenProvider
import org.apache.texera.amber.core.storage.model.LakeFSFileDocument.{
  fileServiceListDirectoryObjectsEndpoint,
  userJwtToken
}
import org.apache.texera.amber.core.storage.util.LakeFSStorageClient
import org.apache.texera.amber.core.storage.util.dataset.GitVersionControlLocalFileStorage

import java.io.{File, FileOutputStream, InputStream}
import java.net._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.IteratorHasAsScala

object LakeFSFileDocument {
  // Requests go to the FileService, which authenticates them as a user. Resolved per read rather
  // than memoised from the pod environment: on a public computing unit the pod has no user of its
  // own, and each run authenticates as whoever started it (see UserTokenProvider). Empty in local
  // development and other architectures, which falls back to reading LakeFS directly.
  def userJwtToken: String = UserTokenProvider.token

  private lazy val datasetPresignEndpoint: String =
    sys.env
      .getOrElse(
        EnvironmentalVariable.ENV_FILE_SERVICE_GET_DATASET_PRESIGNED_URL_ENDPOINT,
        "http://localhost:9092/api/dataset/presign-download"
      )
      .trim

  // The endpoint for listing directory objects from the file service.
  lazy val fileServiceListDirectoryObjectsEndpoint: String =
    sys.env
      .getOrElse(
        EnvironmentalVariable.ENV_FILE_SERVICE_LIST_DIRECTORY_OBJECTS_ENDPOINT,
        "http://localhost:9092/api/dataset/list-directory-objects"
      )
      .trim

  private lazy val modelPresignEndpoint: String =
    sys.env
      .getOrElse(
        EnvironmentalVariable.ENV_FILE_SERVICE_GET_MODEL_PRESIGNED_URL_ENDPOINT,
        "http://localhost:9092/api/model/presign-download"
      )
      .trim

  /**
    * The file-service presign-download endpoint serving this resource type. Each resource type
    * owns an endpoint because they enforce different access control
    */
  def presignEndpointOf(resourceType: ResourceType.Value): String =
    resourceType match {
      case ResourceType.Dataset => datasetPresignEndpoint
      case ResourceType.Model   => modelPresignEndpoint
    }
}

/**
  * A read-only document over a single file stored in a LakeFS repository, addressed by the URI
  * {scheme}:///{repositoryName}/{versionHash}/{fileRelativePath}.
  *
  * Every versioned-file resource (datasets, models, …) reads its files the same way — fetch the
  * bytes through a presigned URL, falling back to a direct LakeFS fetch
  *
  * @param uri          the resolved {scheme}:///{repositoryName}/{versionHash}/{file} URI
  * @param resourceType which resource this file belongs to, selecting the presign endpoint
  * @param isDirectory  when true the URI addresses a directory (repo/versionHash only) whose
  *                     contents are zipped on demand — used by the CloudMapper source operator
  */
private[storage] class LakeFSFileDocument(
    uri: URI,
    val resourceType: ResourceType.Value,
    isDirectory: Boolean = false
) extends VirtualDocument[Nothing]
    with OnVersionedFileResource
    with LazyLogging {

  private val presignEndpoint: String = LakeFSFileDocument.presignEndpointOf(resourceType)
  // Utility function to parse and decode URI segments into individual components
  private def parseUri(uri: URI): (String, String, Path) = {
    val segments = Paths.get(uri.getPath).iterator().asScala.map(_.toString).toArray
    if (!isDirectory && segments.length < 3)
      throw new IllegalArgumentException("URI format is incorrect")

    // parse uri to (repositoryName, versionHash, fileRelativePath)
    val repositoryName = segments(0)
    val versionHash = URLDecoder.decode(segments(1), StandardCharsets.UTF_8)
    if (isDirectory) {
      return (repositoryName, versionHash, Paths.get(""))
    }
    val decodedRelativeSegments =
      segments.drop(2).map(part => URLDecoder.decode(part, StandardCharsets.UTF_8))
    val fileRelativePath = Paths.get(decodedRelativeSegments.head, decodedRelativeSegments.tail: _*)

    (repositoryName, versionHash, fileRelativePath)
  }

  // Extract components from URI using the utility function
  protected val (repositoryName, versionHash, fileRelativePath) = parseUri(uri)

  protected var tempFile: Option[File] = None

  override def getURI: URI = uri

  override def asInputStream(): InputStream = {

    def fallbackToLakeFS(exception: Throwable): InputStream = {
      logger.warn(s"${exception.getMessage}. Falling back to LakeFS direct file fetch.", exception)
      val file = LakeFSStorageClient.getFileFromRepo(
        getRepositoryName(),
        getVersionHash(),
        getFileRelativePath()
      )
      Files.newInputStream(file.toPath)
    }

    if (userJwtToken.isEmpty) {
      try {
        val presignUrl = LakeFSStorageClient.getFilePresignedUrl(
          getRepositoryName(),
          getVersionHash(),
          getFileRelativePath()
        )
        new URL(presignUrl).openStream()
      } catch {
        case e: Exception =>
          fallbackToLakeFS(e)
      }
    } else {
      val presignRequestUrl =
        s"$presignEndpoint?repositoryName=${getRepositoryName()}&commitHash=${getVersionHash()}&filePath=${URLEncoder
          .encode(getFileRelativePath(), StandardCharsets.UTF_8.name())}"

      val connection = new URL(presignRequestUrl).openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("GET")
      connection.setRequestProperty("Authorization", s"Bearer $userJwtToken")

      try {
        val responseCode = connection.getResponseCode
        // A refusal is an answer, not an outage: falling back here would fetch the file with the
        // deployment's own LakeFS credentials and hand the user bytes the file service just said
        // they may not have. Only a failure to *reach* an answer may fall back.
        if (
          responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
          responseCode == HttpURLConnection.HTTP_FORBIDDEN
        ) {
          // Logged, not just thrown: this is the one read failure that does not fall back, so
          // without a line here the only symptom is an operator further downstream reporting
          // that no schema is available, with nothing saying why.
          logger.error(
            s"$presignEndpoint refused the request for ${getFileRelativePath()} in " +
              s"${getRepositoryName()} with HTTP $responseCode. Reading it directly would use " +
              "this deployment's own credentials and return what the refusal withheld, so the " +
              "read fails instead. A 401 usually means the run's token was rejected rather " +
              "than that the user lacks access."
          )
          throw new FileAccessDeniedException(
            s"Not authorized to read ${getFileRelativePath()} from ${getRepositoryName()} " +
              s"(HTTP $responseCode)."
          )
        }
        if (responseCode != HttpURLConnection.HTTP_OK) {
          throw new RuntimeException(
            s"Failed to retrieve presigned URL: HTTP $responseCode"
          )
        }

        // Read response body as a string
        val responseBody =
          new String(connection.getInputStream.readAllBytes(), StandardCharsets.UTF_8)

        // Extract presigned URL from JSON response
        val presignedUrl = responseBody
          .split("\"presignedUrl\"\\s*:\\s*\"")(1)
          .split("\"")(0)

        new URL(presignedUrl).openStream()
      } catch {
        case e: FileAccessDeniedException => throw e
        case e: Exception =>
          fallbackToLakeFS(e)
      } finally {
        connection.disconnect()
      }
    }
  }

  override def asFile(): File = {
    tempFile match {
      case Some(file) => file
      case None =>
        if (isDirectory) {
          // Create a zip file containing all files in the directory
          val tempZipPath = Files.createTempFile("versionedDirectory", ".zip")
          val zipOutputStream = new ZipOutputStream(new FileOutputStream(tempZipPath.toFile))

          try {
            // Get all files in the directory and add them to the zip
            addDirectoryToZip(
              zipOutputStream,
              "",
              getRepositoryName(),
              getVersionHash(),
              fileRelativePath
            )
          } finally {
            zipOutputStream.close()
          }

          val file = tempZipPath.toFile
          tempFile = Some(file)
          file
        } else {
          // Handle single file case
          val tempFilePath = Files.createTempFile("versionedFile", ".tmp")
          val tempFileStream = new FileOutputStream(tempFilePath.toFile)
          val inputStream = asInputStream()

          val buffer = new Array[Byte](1024)

          // Create an iterator to repeatedly call inputStream.read, and direct buffered data to file
          Iterator
            .continually(inputStream.read(buffer))
            .takeWhile(_ != -1)
            .foreach(tempFileStream.write(buffer, 0, _))

          inputStream.close()
          tempFileStream.close()

          val file = tempFilePath.toFile
          tempFile = Some(file)
          file
        }
    }
  }

  override def clear(): Unit = {
    // remove the temporary file, if one was materialized
    tempFile match {
      case Some(file) => Files.delete(file.toPath)
      case None       => // Do nothing
    }
    tempFile = None
  }

  override def getRepositoryName(): String = repositoryName

  override def getVersionHash(): String = versionHash

  override def getFileRelativePath(): String = fileRelativePath.toString

  /**
    * Adds all files from a directory (and its subdirectories) to a zip output stream.
    *
    * @param zipOutputStream The zip output stream to write to
    * @param datasetName The name of the dataset
    * @param versionHash The version hash of the dataset
    * @param directoryPath The relative path of the directory in the dataset
    */
  private def addDirectoryToZip(
      zipOutputStream: ZipOutputStream,
      basePath: String,
      datasetName: String,
      versionHash: String,
      directoryPath: Path
  ): Unit = {
    try {
      // Get all files in the repository
      val allObjects = if (userJwtToken.nonEmpty) {
        // Use FileService endpoint when JWT token is available
        getDirectoryObjectsViaFileService(datasetName, versionHash)
      } else {
        // Fallback to direct LakeFS access
        LakeFSStorageClient.retrieveObjectsOfVersion(datasetName, versionHash)
      }

      val directoryPathStr = directoryPath.toString.replace("\\", "/")

      // Filter objects that are within the specified directory (including subdirectories)
      val objectsInDirectory = allObjects.filter { obj =>
        val objPath = obj.getPath
        if (directoryPathStr.isEmpty) {
          true // Include all files if directory path is empty (root)
        } else {
          objPath.startsWith(directoryPathStr + "/") || objPath == directoryPathStr
        }
      }

      objectsInDirectory.foreach { obj =>
        val objPath = obj.getPath
        val relativePath = if (directoryPathStr.isEmpty) {
          if (basePath.isEmpty) objPath else s"$basePath/$objPath"
        } else {
          val filePathWithinDirectory = objPath.substring(directoryPathStr.length).stripPrefix("/")
          if (basePath.isEmpty) filePathWithinDirectory else s"$basePath/$filePathWithinDirectory"
        }

        // Skip if the relative path is empty (this would be the directory itself)
        if (relativePath.nonEmpty) {
          // Add file to zip
          val zipEntry = new ZipEntry(relativePath)
          zipOutputStream.putNextEntry(zipEntry)

          // Get file content and write to zip
          val fileInputStream = getFileInputStreamFromLakeFS(datasetName, versionHash, objPath)
          val buffer = new Array[Byte](1024)

          try {
            Iterator
              .continually(fileInputStream.read(buffer))
              .takeWhile(_ != -1)
              .foreach(zipOutputStream.write(buffer, 0, _))
          } finally {
            fileInputStream.close()
          }

          zipOutputStream.closeEntry()
        }
      }
    } catch {
      case e: Exception =>
        logger.warn(
          s"Error adding directory to zip via primary method: ${e.getMessage}. Trying fallback.",
          e
        )
        // Fallback: try to get files using alternative method.
        // NOTE: fallback throws if it also fails, so the empty-zip-with-swallowed-error scenario is avoided.
        addDirectoryToZipFallback(
          zipOutputStream,
          basePath,
          datasetName,
          versionHash,
          directoryPath
        )
    }
  }

  /**
    * Gets directory objects via FileService when JWT token is available.
    */
  private def getDirectoryObjectsViaFileService(
      datasetName: String,
      versionHash: String
  ): List[io.lakefs.clients.sdk.model.ObjectStats] = {
    val requestUrl =
      s"$fileServiceListDirectoryObjectsEndpoint?datasetName=${URLEncoder.encode(datasetName, StandardCharsets.UTF_8.name())}&commitHash=${URLEncoder
        .encode(versionHash, StandardCharsets.UTF_8.name())}"

    val connection = new URL(requestUrl).openConnection().asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Authorization", s"Bearer $userJwtToken")

    try {
      if (connection.getResponseCode != HttpURLConnection.HTTP_OK) {
        throw new RuntimeException(
          s"Failed to list directory objects: HTTP ${connection.getResponseCode}"
        )
      }

      // Read response body as a string
      val responseBody =
        new String(connection.getInputStream.readAllBytes(), StandardCharsets.UTF_8)

      // Parse JSON response to extract objects
      // Expecting format: {"objects": [{"path": "...", "sizeBytes": ...}, ...]}
      val objectsStartPattern = """"objects"\s*:\s*\[""".r
      val objectPattern = """\{"path"\s*:\s*"([^"]+)"\s*,\s*"sizeBytes"\s*:\s*(\d+)\}""".r

      val objectMatches = objectPattern.findAllMatchIn(responseBody).toList
      objectMatches.map { matchObj =>
        val path = matchObj.group(1)
        val sizeBytes = matchObj.group(2).toLong

        // Create a mock ObjectStats object since we only need path and size
        val objectStats = new io.lakefs.clients.sdk.model.ObjectStats()
        objectStats.setPath(path)
        objectStats.setSizeBytes(sizeBytes)
        objectStats
      }

    } catch {
      case e: Exception =>
        logger.warn(
          s"Failed to get directory objects via FileService: ${e.getMessage}. Falling back to direct LakeFS.",
          e
        )
        // Fallback to direct LakeFS access
        LakeFSStorageClient.retrieveObjectsOfVersion(datasetName, versionHash)
    } finally {
      connection.disconnect()
    }
  }

  /**
    * Fallback method to add directory files to zip using local file system access.
    */
  private def addDirectoryToZipFallback(
      zipOutputStream: ZipOutputStream,
      basePath: String,
      datasetName: String,
      versionHash: String,
      directoryPath: Path
  ): Unit = {
    try {
      // Use GitVersionControlLocalFileStorage as fallback
      val datasetPath =
        PathUtils.getDatasetPath(0) // This might need to be adjusted based on actual dataset ID
      val fullDirectoryPath = datasetPath.resolve(directoryPath)

      if (Files.exists(fullDirectoryPath) && Files.isDirectory(fullDirectoryPath)) {
        Files.walk(fullDirectoryPath).forEach { filePath =>
          if (!Files.isDirectory(filePath)) {
            val relativePath = datasetPath.relativize(filePath).toString.replace("\\", "/")
            val zipRelativePath = if (basePath.isEmpty) {
              directoryPath.relativize(datasetPath.relativize(filePath)).toString.replace("\\", "/")
            } else {
              s"$basePath/${directoryPath.relativize(datasetPath.relativize(filePath)).toString.replace("\\", "/")}"
            }

            val zipEntry = new ZipEntry(zipRelativePath)
            zipOutputStream.putNextEntry(zipEntry)

            val fileInputStream =
              GitVersionControlLocalFileStorage.retrieveFileContentOfVersionAsInputStream(
                datasetPath,
                versionHash,
                filePath
              )

            val buffer = new Array[Byte](1024)
            try {
              Iterator
                .continually(fileInputStream.read(buffer))
                .takeWhile(_ != -1)
                .foreach(zipOutputStream.write(buffer, 0, _))
            } finally {
              fileInputStream.close()
            }

            zipOutputStream.closeEntry()
          }
        }
      }
    } catch {
      case e: Exception =>
        logger.error(s"Fallback method also failed for directory zipping: ${e.getMessage}", e)
        throw new RuntimeException(s"Failed to create zip file for directory: ${directoryPath}", e)
    }
  }

  /**
    * Gets an input stream for a file from LakeFS.
    */
  private def getFileInputStreamFromLakeFS(
      datasetName: String,
      versionHash: String,
      filePath: String
  ): InputStream = {
    if (userJwtToken.isEmpty) {
      val presignUrl = LakeFSStorageClient.getFilePresignedUrl(datasetName, versionHash, filePath)
      new URL(presignUrl).openStream()
    } else {
      val presignRequestUrl =
        s"$presignEndpoint?repositoryName=${datasetName}&commitHash=${versionHash}&filePath=${URLEncoder
          .encode(filePath, StandardCharsets.UTF_8.name())}"

      val connection = new URL(presignRequestUrl).openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("GET")
      connection.setRequestProperty("Authorization", s"Bearer $userJwtToken")

      if (connection.getResponseCode != HttpURLConnection.HTTP_OK) {
        throw new RuntimeException(
          s"Failed to retrieve presigned URL: HTTP ${connection.getResponseCode}"
        )
      }

      val responseBody =
        new String(connection.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val presignedUrl = responseBody.split("\"presignedUrl\"\\s*:\\s*\"")(1).split("\"")(0)

      connection.disconnect()
      new URL(presignedUrl).openStream()
    }
  }
}
