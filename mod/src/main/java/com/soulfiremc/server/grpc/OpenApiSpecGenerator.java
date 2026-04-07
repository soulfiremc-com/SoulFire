/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.MessageOrBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.*;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.grpc.generated.LoginServiceGrpc;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class OpenApiSpecGenerator {
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final String HTTP_SERVICE_SUFFIX = "_HTTP";
  private static final String OPEN_API_VERSION = "3.0.3";
  private static final String ERROR_SCHEMA_NAME = "SoulFireGrpcError";
  private static final List<DocServicePlugin> DOC_SERVICE_PLUGINS = immutableServiceLoader(
    DocServicePlugin.class,
    OpenApiSpecGenerator.class.getClassLoader()
  );
  private static final List<DescriptiveTypeInfoProvider> DESCRIPTIVE_TYPE_INFO_PROVIDERS = immutableServiceLoader(
    DescriptiveTypeInfoProvider.class,
    OpenApiSpecGenerator.class.getClassLoader()
  );

  private final ServiceSpecification specification;
  private final String publicAddress;
  private final Map<String, ProtoMethodMetadata> protoMethodMetadata;
  private final Map<String, ServiceInfo> rawServiceByName;
  private final Map<String, StructInfo> structByTypeName;
  private final Map<String, EnumInfo> enumByTypeName;
  private final Map<String, String> canonicalSchemaNameByTypeName;
  private final Map<String, String> docStrings;
  private final Set<String> usedSchemaNames = new TreeSet<>();

  private OpenApiSpecGenerator(
    ServiceSpecification specification,
    String publicAddress,
    Map<String, ProtoMethodMetadata> protoMethodMetadata
  ) {
    this.specification = specification;
    this.publicAddress = normalizeBaseUrl(publicAddress);
    this.protoMethodMetadata = protoMethodMetadata;
    this.rawServiceByName = specification.services().stream()
      .filter(service -> !service.name().endsWith(HTTP_SERVICE_SUFFIX))
      .collect(Collectors.toMap(ServiceInfo::name, Function.identity()));
    this.structByTypeName = createStructLookup(specification.structs());
    this.enumByTypeName = specification.enums().stream()
      .collect(Collectors.toMap(EnumInfo::name, Function.identity()));
    this.canonicalSchemaNameByTypeName = createCanonicalSchemaLookup(specification.structs(), specification.enums());
    this.docStrings = specification.docStrings().entrySet().stream()
      .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().docString(), (a, _) -> a));
  }

  static ObjectNode generate(List<ServiceConfig> services, String publicAddress) {
    var serviceSpecification = buildServiceSpecification(services);
    var protoMethodMetadata = loadProtoMethodMetadata(services);
    return new OpenApiSpecGenerator(serviceSpecification, publicAddress, protoMethodMetadata).generate();
  }

  private ObjectNode generate() {
    var document = JSON_MAPPER.createObjectNode();
    document.put("openapi", OPEN_API_VERSION);
    document.set("info", buildInfo());
    document.set("servers", buildServers());
    document.set("tags", buildTags());
    document.set("paths", buildPaths());
    document.set("components", buildComponents());
    document.set("security", buildDefaultSecurity());
    document.set("externalDocs", buildExternalDocs());
    document.set("x-soulfire", buildSoulFireExtension());
    return document;
  }

  private ObjectNode buildInfo() {
    var info = JSON_MAPPER.createObjectNode();
    info.put("title", "SoulFire HTTP API");
    info.put("version", BuildData.VERSION);
    info.put(
      "description",
      """
        Generated from SoulFire's Armeria gRPC metadata.

        This document only covers HTTP-accessible unary operations:
        - unframed JSON POST routes generated from gRPC services
        - additional HTTP/JSON transcoding routes exposed by Armeria

        It does not describe streaming RPCs, gRPC-Web framing, reflection, or other non-HTTP transports.

        Authorization:
        - Bearer: `Authorization: Bearer <api-jwt>`
        - Basic: `Authorization: Basic <base64(username:api-jwt)>`

        `LoginService` is public. Every other operation requires an API token audience accepted by SoulFire.
        """
    );
    return info;
  }

  private ArrayNode buildServers() {
    var servers = JSON_MAPPER.createArrayNode();
    servers.addObject()
      .put("url", publicAddress)
      .put("description", "Configured SoulFire public API address");
    return servers;
  }

  private ArrayNode buildTags() {
    var tags = JSON_MAPPER.createArrayNode();

    specification.services().stream()
      .map(ServiceInfo::name)
      .map(OpenApiSpecGenerator::baseServiceName)
      .distinct()
      .sorted()
      .forEach(serviceName -> {
        var tag = tags.addObject();
        tag.put("name", simpleName(serviceName));
        var description = docString(serviceName);
        if (!description.isBlank()) {
          tag.put("description", description);
        }
      });

    return tags;
  }

  private ObjectNode buildPaths() {
    var paths = JSON_MAPPER.createObjectNode();

    specification.services().stream()
      .sorted(Comparator.comparing(ServiceInfo::name))
      .forEach(service -> {
        var transcoded = isTranscodedService(service);
        var baseServiceName = baseServiceName(service);
        var rawService = rawServiceByName.get(baseServiceName);

        service.methods().stream()
          .sorted(Comparator.comparing(MethodInfo::name).thenComparing(method -> method.httpMethod().name()))
          .forEach(method -> addOperations(paths, service, rawService, method, transcoded));
      });

    return paths;
  }

  private void addOperations(
    ObjectNode paths,
    ServiceInfo service,
    ServiceInfo rawService,
    MethodInfo method,
    boolean transcoded
  ) {
    var methodKey = methodKey(baseServiceName(service), method.name());
    var metadata = protoMethodMetadata.get(methodKey);
    if (metadata == null || !metadata.unary()) {
      return;
    }

    var endpoints = method.endpoints().stream()
      .sorted(Comparator.comparing(EndpointInfo::pathMapping))
      .toList();
    if (endpoints.isEmpty()) {
      return;
    }

    var examplePaths = method.examplePaths();
    for (var index = 0; index < endpoints.size(); index++) {
      var endpoint = endpoints.get(index);
      var path = openApiPath(endpoint, examplePaths, index);
      var pathItem = literalPathItem(paths, path);
      var httpMethod = method.httpMethod().name().toLowerCase(Locale.ROOT);
      if (pathItem.has(httpMethod)) {
        continue;
      }

      pathItem.set(httpMethod, buildOperation(service, rawService, method, metadata, transcoded, index));
    }
  }

  private ObjectNode buildOperation(
    ServiceInfo service,
    ServiceInfo rawService,
    MethodInfo method,
    ProtoMethodMetadata metadata,
    boolean transcoded,
    int routeIndex
  ) {
    var baseServiceName = baseServiceName(service);
    var inputStruct = findInputStruct(rawService, method, metadata);
    var operation = JSON_MAPPER.createObjectNode();

    var description = operationDescription(baseServiceName, method.name(), transcoded);
    var summary = firstSentence(description);
    if (!summary.isBlank()) {
      operation.put("summary", summary);
    }
    if (!description.isBlank()) {
      operation.put("description", description);
    }

    operation.put("operationId", operationId(baseServiceName, method, routeIndex));
    operation.put("x-soulfire-transport", transcoded ? "http-json-transcoding" : "unframed-grpc-json");
    operation.put("x-soulfire-grpc-service", baseServiceName);
    operation.put("x-soulfire-grpc-method", method.name());
    operation.put("x-soulfire-unary-only", true);
    operation.putArray("tags").add(simpleName(baseServiceName));

    if (LoginServiceGrpc.SERVICE_NAME.equals(baseServiceName)) {
      operation.set("security", JSON_MAPPER.createArrayNode());
    }

    var parameters = buildOperationParameters(baseServiceName, method, inputStruct);
    if (!parameters.isEmpty()) {
      operation.set("parameters", parameters);
    }

    var requestBody = buildRequestBody(baseServiceName, rawService, method, inputStruct, transcoded);
    if (requestBody != null) {
      operation.set("requestBody", requestBody);
    }

    operation.set("responses", buildResponses(baseServiceName, method, metadata));
    return operation;
  }

  private ArrayNode buildOperationParameters(String serviceName, MethodInfo method, StructInfo inputStruct) {
    var parameters = JSON_MAPPER.createArrayNode();

    method.parameters().stream()
      .filter(parameter -> parameter.location() == FieldLocation.PATH || parameter.location() == FieldLocation.QUERY)
      .forEach(parameter -> {
        var parameterNode = parameters.addObject();
        parameterNode.put("name", parameter.name());
        parameterNode.put("in", parameter.location() == FieldLocation.PATH ? "path" : "query");
        parameterNode.put("required", parameter.location() == FieldLocation.PATH
          || parameter.requirement() == FieldRequirement.REQUIRED);

        var description = parameterDescription(serviceName, method.name(), parameter.name(), inputStruct);
        if (!description.isBlank()) {
          parameterNode.put("description", description);
        }

        parameterNode.set("schema", schemaForType(parameter.typeSignature()));
      });

    return parameters;
  }

  private ObjectNode buildRequestBody(
    String serviceName,
    ServiceInfo rawService,
    MethodInfo method,
    StructInfo inputStruct,
    boolean transcoded
  ) {
    ObjectNode schema = null;
    var requestExample = firstRawRequestExample(rawService, method.name());

    if (!transcoded) {
      if (method.useParameterAsRoot() && method.parameters().size() == 1) {
        schema = schemaForType(method.parameters().get(0).typeSignature());
      } else if (!method.parameters().isEmpty()) {
        schema = objectSchemaForParameters(serviceName, method.name(), method.parameters(), inputStruct);
      }
    } else {
      var bodyParameters = method.parameters().stream()
        .filter(parameter -> parameter.location() == FieldLocation.BODY)
        .toList();
      if (!bodyParameters.isEmpty()) {
        schema = objectSchemaForParameters(serviceName, method.name(), bodyParameters, inputStruct);
        if (method.parameters().stream().anyMatch(parameter ->
          parameter.location() == FieldLocation.PATH || parameter.location() == FieldLocation.QUERY)) {
          requestExample = null;
        }
      }
    }

    if (schema == null) {
      return null;
    }

    var content = JSON_MAPPER.createObjectNode();
    var jsonContent = content.putObject("application/json");
    jsonContent.set("schema", schema);
    if (requestExample != null && !requestExample.isBlank()) {
      jsonContent.set("example", tryReadJson(requestExample));
    }

    var requestBody = JSON_MAPPER.createObjectNode();
    requestBody.put("required", true);
    requestBody.set("content", content);
    return requestBody;
  }

  private ObjectNode objectSchemaForParameters(
    String serviceName,
    String methodName,
    List<ParamInfo> parameters,
    StructInfo inputStruct
  ) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    var properties = schema.putObject("properties");
    var required = schema.putArray("required");

    parameters.forEach(parameter -> {
      properties.set(parameter.name(), schemaWithDescription(
        schemaForType(parameter.typeSignature()),
        parameterDescription(serviceName, methodName, parameter.name(), inputStruct)
      ));
      var description = parameterDescription(serviceName, methodName, parameter.name(), inputStruct);
      if (parameter.requirement() == FieldRequirement.REQUIRED) {
        required.add(parameter.name());
      }
    });

    return schema;
  }

  private ObjectNode buildResponses(String serviceName, MethodInfo method, ProtoMethodMetadata metadata) {
    var responses = JSON_MAPPER.createObjectNode();
    var successResponse = responses.putObject("200");
    var returnDescription = docString("%s/%s:return".formatted(serviceName, method.name()));
    successResponse.put("description", returnDescription.isBlank() ? "Successful response." : returnDescription);
    successResponse.putObject("content")
      .putObject("application/json")
      .set("schema", schemaForType(method.returnTypeSignature()));

    if (!LoginServiceGrpc.SERVICE_NAME.equals(serviceName)) {
      responses.set("401", errorResponse("Missing or invalid API authorization header."));
      responses.set("403", errorResponse("The authenticated user does not have the required permission."));
    }

    responses.set("500", errorResponse("Unexpected server-side failure while handling the RPC."));
    if (LoginServiceGrpc.SERVICE_NAME.equals(serviceName)) {
      responses.set("429", errorResponse("Login flow or credential validation was rate limited."));
    }

    if (!metadata.declaredExceptions().isEmpty()) {
      responses.putObject("default")
        .put("description", "gRPC failure mapped to HTTP by Armeria's JSON unframed error handler.")
        .putObject("content")
        .putObject("application/json")
        .set("schema", schemaRef(ERROR_SCHEMA_NAME));
    }

    return responses;
  }

  private ObjectNode errorResponse(String description) {
    var response = JSON_MAPPER.createObjectNode();
    response.put("description", description);
    response.putObject("content")
      .putObject("application/json")
      .set("schema", schemaRef(ERROR_SCHEMA_NAME));
    return response;
  }

  private ObjectNode buildComponents() {
    var components = JSON_MAPPER.createObjectNode();
    components.set("securitySchemes", buildSecuritySchemes());

    var schemas = components.putObject("schemas");
    schemas.set(ERROR_SCHEMA_NAME, buildErrorSchema());

    var pending = new ArrayDeque<>(usedSchemaNames);
    var generated = new HashSet<String>();
    while (!pending.isEmpty()) {
      var schemaName = pending.removeFirst();
      if (!generated.add(schemaName)) {
        continue;
      }

      var struct = structByTypeName.get(schemaName);
      if (struct != null) {
        schemas.set(schemaName, buildStructSchema(struct, pending, generated));
        continue;
      }

      var enumInfo = enumByTypeName.get(schemaName);
      if (enumInfo != null) {
        schemas.set(schemaName, buildEnumSchema(enumInfo));
      }
    }

    return components;
  }

  private ObjectNode buildStructSchema(StructInfo struct, Deque<String> pending, Set<String> generated) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    var description = firstNonBlank(struct.descriptionInfo().docString(), docString(struct.name()));
    if (!description.isBlank()) {
      schema.put("description", description);
    }

    var properties = schema.putObject("properties");
    var required = schema.putArray("required");
    for (var field : struct.fields()) {
      var fieldDescription = firstNonBlank(
        field.descriptionInfo().docString(),
        docString("%s/%s".formatted(struct.name(), field.name()))
      );
      var fieldSchema = schemaWithDescription(schemaForType(field.typeSignature()), fieldDescription);
      properties.set(field.name(), fieldSchema);

      collectReferencedSchemaNames(field.typeSignature(), pending, generated);
      if (field.requirement() == FieldRequirement.REQUIRED) {
        required.add(field.name());
      }
    }

    return schema;
  }

  private ObjectNode buildEnumSchema(EnumInfo enumInfo) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "string");
    var description = firstNonBlank(enumInfo.descriptionInfo().docString(), docString(enumInfo.name()));
    if (!description.isBlank()) {
      schema.put("description", description);
    }
    var values = schema.putArray("enum");
    enumInfo.values().forEach(value -> values.add(value.name()));
    return schema;
  }

  private ObjectNode buildErrorSchema() {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    var properties = schema.putObject("properties");
    properties.putObject("code")
      .put("type", "integer")
      .put("format", "int32")
      .put("description", "Numeric gRPC status code.");
    properties.putObject("grpc-code")
      .put("type", "string")
      .put("description", "Named gRPC status code.");
    properties.putObject("message")
      .put("type", "string")
      .put("description", "Human-readable error message.");
    properties.putObject("stack-trace")
      .put("type", "string")
      .put("description", "Stack trace included only when verbose responses are enabled.");
    properties.putObject("details")
      .put("type", "array")
      .put("description", "Optional Google RPC error detail payloads.")
      .putObject("items")
      .put("type", "object")
      .put("additionalProperties", true);

    var required = schema.putArray("required");
    required.add("code");
    required.add("grpc-code");
    return schema;
  }

  private ObjectNode buildSecuritySchemes() {
    var securitySchemes = JSON_MAPPER.createObjectNode();

    securitySchemes.putObject("bearerAuth")
      .put("type", "http")
      .put("scheme", "bearer")
      .put("bearerFormat", "JWT")
      .put("description", "Preferred API authentication. Use a JWT with the `api` audience.");

    securitySchemes.putObject("basicTokenAuth")
      .put("type", "http")
      .put("scheme", "basic")
      .put(
        "description",
        "Compatibility authentication. Encode `username:api-jwt` as the HTTP Basic credentials. SoulFire ignores the username and validates the token in the password slot."
      );

    return securitySchemes;
  }

  private ArrayNode buildDefaultSecurity() {
    var security = JSON_MAPPER.createArrayNode();
    security.addObject().putArray("bearerAuth");
    security.addObject().putArray("basicTokenAuth");
    return security;
  }

  private ObjectNode buildExternalDocs() {
    return JSON_MAPPER.createObjectNode()
      .put("description", "Interactive Armeria DocService")
      .put("url", appendPath(publicAddress, "/docs"));
  }

  private ObjectNode buildSoulFireExtension() {
    var extension = JSON_MAPPER.createObjectNode();
    extension.put("httpJsonTranscodingEnabled", true);
    extension.put("unframedJsonEnabled", true);
    extension.put("openApiDocument", appendPath(publicAddress, "/openapi.json"));
    extension.put("docService", appendPath(publicAddress, "/docs"));
    extension.put("mcp", appendPath(publicAddress, "/mcp"));

    var limitations = extension.putArray("limitations");
    limitations.add("Only unary RPC methods are included.");
    limitations.add("Streaming RPCs stay gRPC-only and are intentionally excluded.");
    limitations.add("Operations describe the JSON HTTP surface, not framed gRPC semantics.");

    var authorization = extension.putObject("authorization");
    authorization.put("publicService", LoginServiceGrpc.SERVICE_NAME);
    authorization.put("bearerHeader", "Authorization: Bearer <api-jwt>");
    authorization.put("basicHeader", "Authorization: Basic <base64(username:api-jwt)>");
    return extension;
  }

  private String operationDescription(String serviceName, String methodName, boolean transcoded) {
    var methodDescription = docString("%s/%s".formatted(serviceName, methodName));
    var authDescription = LoginServiceGrpc.SERVICE_NAME.equals(serviceName)
      ? "Authentication: public login endpoint."
      : "Authentication: requires a SoulFire API token via Bearer or Basic auth.";
    var transportDescription = transcoded
      ? "Transport: Armeria HTTP/JSON transcoding route."
      : "Transport: Armeria unframed JSON POST route.";

    return Stream.of(methodDescription, authDescription, transportDescription)
      .filter(value -> value != null && !value.isBlank())
      .collect(Collectors.joining("\n\n"));
  }

  private String parameterDescription(
    String serviceName,
    String methodName,
    String parameterName,
    StructInfo inputStruct
  ) {
    var direct = docString("%s/%s:param/%s".formatted(serviceName, methodName, parameterName));
    if (!direct.isBlank()) {
      return direct;
    }

    if (inputStruct == null) {
      return "";
    }

    return fieldDescription(inputStruct, parameterName);
  }

  private String fieldDescription(StructInfo struct, String fieldPath) {
    if (struct == null || fieldPath.isBlank()) {
      return "";
    }

    var parts = fieldPath.split("\\.");
    StructInfo currentStruct = struct;
    FieldInfo currentField = null;
    for (var part : parts) {
      currentField = currentStruct.fields().stream()
        .filter(field -> field.name().equals(part))
        .findFirst()
        .orElse(null);
      if (currentField == null) {
        return "";
      }

      if (!part.equals(parts[parts.length - 1])) {
        currentStruct = structByTypeName.get(currentField.typeSignature().signature());
        if (currentStruct == null) {
          return "";
        }
      }
    }

    if (currentField == null) {
      return "";
    }

    return firstNonBlank(
      currentField.descriptionInfo().docString(),
      docString("%s/%s".formatted(currentStruct.name(), currentField.name()))
    );
  }

  private StructInfo findInputStruct(ServiceInfo rawService, MethodInfo method, ProtoMethodMetadata metadata) {
    if (rawService != null) {
      var rawMethod = rawService.methods().stream()
        .filter(candidate -> candidate.name().equals(method.name()))
        .findFirst()
        .orElse(null);
      if (rawMethod != null && rawMethod.useParameterAsRoot() && rawMethod.parameters().size() == 1) {
        return structByTypeName.get(rawMethod.parameters().get(0).typeSignature().signature());
      }
    }

    return structByTypeName.get(metadata.inputTypeName());
  }

  private String firstRawRequestExample(ServiceInfo rawService, String methodName) {
    if (rawService == null) {
      return null;
    }

    return rawService.methods().stream()
      .filter(method -> method.name().equals(methodName))
      .flatMap(method -> method.exampleRequests().stream())
      .filter(example -> !example.isBlank())
      .findFirst()
      .orElse(null);
  }

  private ObjectNode schemaForType(TypeSignature typeSignature) {
    if (typeSignature instanceof MapTypeSignature mapTypeSignature) {
      var schema = JSON_MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.set("additionalProperties", schemaForType(mapTypeSignature.valueTypeSignature()));
      collectReferencedSchemaNames(mapTypeSignature.valueTypeSignature(), null, null);
      return schema;
    }

    if (typeSignature instanceof ContainerTypeSignature containerTypeSignature) {
      if (typeSignature.type() == TypeSignatureType.OPTIONAL && !containerTypeSignature.typeParameters().isEmpty()) {
        var schema = schemaForType(containerTypeSignature.typeParameters().get(0));
        schema.put("nullable", true);
        return schema;
      }

      var containerName = typeSignature.name().toLowerCase(Locale.ROOT);
      if (typeSignature.type() == TypeSignatureType.ITERABLE
        || List.of("repeated", "list", "array", "set").contains(containerName)) {
        var schema = JSON_MAPPER.createObjectNode();
        schema.put("type", "array");
        var itemType = containerTypeSignature.typeParameters().get(0);
        schema.set("items", schemaForType(itemType));
        collectReferencedSchemaNames(itemType, null, null);
        return schema;
      }
    }

    if (typeSignature.type() == TypeSignatureType.ENUM || typeSignature.type() == TypeSignatureType.STRUCT) {
      var schemaName = canonicalSchemaNameByTypeName.getOrDefault(typeSignature.signature(), typeSignature.name());
      usedSchemaNames.add(schemaName);
      return schemaRef(schemaName);
    }

    if (typeSignature.type() == TypeSignatureType.BASE) {
      return baseSchema(typeSignature.name());
    }

    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", true);
    return schema;
  }

  private void collectReferencedSchemaNames(
    TypeSignature typeSignature,
    Deque<String> pending,
    Set<String> generated
  ) {
    if (typeSignature.type() == TypeSignatureType.STRUCT || typeSignature.type() == TypeSignatureType.ENUM) {
      var schemaName = canonicalSchemaNameByTypeName.getOrDefault(typeSignature.signature(), typeSignature.name());
      if (usedSchemaNames.add(schemaName) && pending != null && generated != null && !generated.contains(schemaName)) {
        pending.add(schemaName);
      }
      return;
    }

    if (typeSignature instanceof MapTypeSignature mapTypeSignature) {
      collectReferencedSchemaNames(mapTypeSignature.valueTypeSignature(), pending, generated);
      return;
    }

    if (typeSignature instanceof ContainerTypeSignature containerTypeSignature) {
      containerTypeSignature.typeParameters().forEach(type ->
        collectReferencedSchemaNames(type, pending, generated));
    }
  }

  private static ObjectNode baseSchema(String typeName) {
    var normalized = typeName.toLowerCase(Locale.ROOT);
    var schema = JSON_MAPPER.createObjectNode();

    switch (normalized) {
      case "boolean", "bool" -> schema.put("type", "boolean");
      case "float" -> {
        schema.put("type", "number");
        schema.put("format", "float");
      }
      case "double", "number" -> {
        schema.put("type", "number");
        schema.put("format", "double");
      }
      case "i", "i8", "i16", "i32", "integer", "int", "int32", "uint32", "sint32",
           "fixed32", "sfixed32" -> {
        schema.put("type", "integer");
        schema.put("format", "int32");
      }
      case "i64", "int64", "uint64", "sint64", "fixed64", "sfixed64", "long", "long64", "l64" -> {
        schema.put("type", "integer");
        schema.put("format", "int64");
      }
      case "byte", "bytes", "binary" -> {
        schema.put("type", "string");
        schema.put("format", "byte");
      }
      case "string" -> schema.put("type", "string");
      default -> {
        schema.put("type", "object");
        schema.put("additionalProperties", true);
      }
    }

    return schema;
  }

  private static ObjectNode schemaRef(String schemaName) {
    return JSON_MAPPER.createObjectNode()
      .put("$ref", "#/components/schemas/" + escapeJsonPointerSegment(schemaName));
  }

  private static ObjectNode schemaWithDescription(ObjectNode schema, String description) {
    if (description == null || description.isBlank()) {
      return schema;
    }

    if (!schema.has("$ref")) {
      schema.put("description", description);
      return schema;
    }

    var wrapper = JSON_MAPPER.createObjectNode();
    wrapper.put("description", description);
    wrapper.putArray("allOf").add(schema);
    return wrapper;
  }

  private static String operationId(String serviceName, MethodInfo method, int routeIndex) {
    var base = sanitizeIdentifier("%s_%s_%s".formatted(serviceName, method.name(), method.httpMethod().name()));
    return routeIndex == 0 ? base : base + "_route" + (routeIndex + 1);
  }

  private static String openApiPath(EndpointInfo endpoint, List<String> examplePaths, int index) {
    if (index < examplePaths.size() && !examplePaths.get(index).isBlank()) {
      return normalizePath(examplePaths.get(index));
    }

    return normalizePath(endpoint.pathMapping());
  }

  static ObjectNode literalPathItem(ObjectNode paths, String path) {
    var existing = paths.get(path);
    if (existing instanceof ObjectNode objectNode) {
      return objectNode;
    }
    if (existing != null) {
      throw new IllegalStateException("OpenAPI path '%s' is not an object node".formatted(path));
    }

    return paths.putObject(path);
  }

  private static String methodKey(String serviceName, String methodName) {
    return serviceName + '/' + methodName;
  }

  private static boolean isTranscodedService(ServiceInfo serviceInfo) {
    return serviceInfo.name().endsWith(HTTP_SERVICE_SUFFIX);
  }

  private static String baseServiceName(ServiceInfo serviceInfo) {
    return baseServiceName(serviceInfo.name());
  }

  private static String baseServiceName(String serviceName) {
    if (serviceName.endsWith(HTTP_SERVICE_SUFFIX)) {
      return serviceName.substring(0, serviceName.length() - HTTP_SERVICE_SUFFIX.length());
    }

    return serviceName;
  }

  private static String simpleName(String fullName) {
    var lastDot = fullName.lastIndexOf('.');
    return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
  }

  private String docString(String key) {
    return docStrings.getOrDefault(key, "");
  }

  private static String firstSentence(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }

    var trimmed = value.trim();
    for (var i = 0; i < trimmed.length(); i++) {
      if (trimmed.charAt(i) == '.' && (i + 1 == trimmed.length() || Character.isWhitespace(trimmed.charAt(i + 1)))) {
        return trimmed.substring(0, i + 1);
      }
    }

    return trimmed;
  }

  private static String firstNonBlank(String... values) {
    for (var value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }

    return "";
  }

  private static String appendPath(String baseUrl, String path) {
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1) + path;
    }

    return baseUrl + path;
  }

  private static String normalizeBaseUrl(String url) {
    if (url.endsWith("/")) {
      return url.substring(0, url.length() - 1);
    }

    return url;
  }

  private static String normalizePath(String path) {
    if (path.startsWith("^")) {
      path = path.substring(1);
    }
    if (path.endsWith("$")) {
      path = path.substring(0, path.length() - 1);
    }
    if (!path.startsWith("/")) {
      path = '/' + path;
    }
    return path;
  }

  private static String sanitizeIdentifier(String value) {
    return value.replaceAll("[^A-Za-z0-9_]+", "_");
  }

  private static String escapeJsonPointerSegment(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static com.fasterxml.jackson.databind.JsonNode tryReadJson(String value) {
    try {
      return JSON_MAPPER.readTree(value);
    } catch (Exception ignored) {
      return JSON_MAPPER.getNodeFactory().textNode(value);
    }
  }

  private static ServiceSpecification buildServiceSpecification(List<ServiceConfig> services) {
    var docStrings = new LinkedHashMap<String, DescriptionInfo>();
    var specifications = new ArrayList<ServiceSpecification>();
    var descriptiveTypeInfoProvider = composeDescriptiveTypeInfoProvider();

    for (var plugin : DOC_SERVICE_PLUGINS) {
      var supportedServices = findSupportedServices(plugin, services);
      if (supportedServices.isEmpty()) {
        continue;
      }

      specifications.add(plugin.generateSpecification(supportedServices, DocServiceFilter.ofGrpc(), descriptiveTypeInfoProvider));
      plugin.loadDocStrings(supportedServices).forEach(docStrings::putIfAbsent);
    }

    var merged = ServiceSpecification.merge(specifications, null);
    return new ServiceSpecification(
      merged.services(),
      merged.enums(),
      merged.structs(),
      merged.exceptions(),
      List.of(
        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Bearer <api-jwt>"),
        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic <base64(username:api-jwt)>")
      ),
      docStrings,
      null
    );
  }

  private static Map<String, ProtoMethodMetadata> loadProtoMethodMetadata(List<ServiceConfig> services) {
    var metadata = new LinkedHashMap<String, ProtoMethodMetadata>();
    var processedServices = new HashSet<String>();

    for (var serviceConfig : services) {
      var grpcService = serviceConfig.service().as(GrpcService.class);
      if (grpcService == null) {
        continue;
      }

      for (ServerServiceDefinition serviceDefinition : grpcService.services()) {
        var grpcServiceDescriptor = serviceDefinition.getServiceDescriptor();
        if (!processedServices.add(grpcServiceDescriptor.getName())) {
          continue;
        }

        if (!(grpcServiceDescriptor.getSchemaDescriptor() instanceof ProtoFileDescriptorSupplier supplier)) {
          continue;
        }

        FileDescriptor fileDescriptor = supplier.getFileDescriptor();
        var protoServiceDescriptor = fileDescriptor.getServices().stream()
          .filter(descriptor -> descriptor.getFullName().equals(grpcServiceDescriptor.getName()))
          .findFirst()
          .orElse(null);
        if (protoServiceDescriptor == null) {
          continue;
        }

        for (MethodDescriptor method : protoServiceDescriptor.getMethods()) {
          metadata.putIfAbsent(
            methodKey(protoServiceDescriptor.getFullName(), method.getName()),
            new ProtoMethodMetadata(
              protoServiceDescriptor.getFullName(),
              method.getName(),
              method.getInputType().getFullName(),
              method.getOutputType().getFullName(),
              !method.isClientStreaming() && !method.isServerStreaming(),
              List.of()
            )
          );
        }
      }
    }

    return metadata;
  }

  private static Map<String, StructInfo> createStructLookup(Set<StructInfo> structs) {
    var structLookup = new HashMap<String, StructInfo>();
    for (var struct : structs) {
      structLookup.put(struct.name(), struct);
      if (struct.alias() != null && !struct.alias().equals(struct.name())) {
        structLookup.put(struct.alias(), struct);
      }
    }
    return structLookup;
  }

  private static Map<String, String> createCanonicalSchemaLookup(Set<StructInfo> structs, Set<EnumInfo> enums) {
    var schemaLookup = new HashMap<String, String>();
    structs.forEach(struct -> {
      schemaLookup.put(struct.name(), struct.name());
      if (struct.alias() != null && !struct.alias().equals(struct.name())) {
        schemaLookup.put(struct.alias(), struct.name());
      }
    });
    enums.forEach(enumInfo -> schemaLookup.put(enumInfo.name(), enumInfo.name()));
    return schemaLookup;
  }

  private static Set<ServiceConfig> findSupportedServices(DocServicePlugin plugin, List<ServiceConfig> services) {
    var supportedServiceTypes = plugin.supportedServiceTypes();
    return services.stream()
      .filter(serviceConfig -> supportedServiceTypes.stream().anyMatch(type -> serviceConfig.service().as(type) != null))
      .collect(Collectors.toSet());
  }

  private static DescriptiveTypeInfoProvider composeDescriptiveTypeInfoProvider() {
    return typeDescriptor -> {
      for (var provider : DESCRIPTIVE_TYPE_INFO_PROVIDERS) {
        var descriptiveTypeInfo = provider.newDescriptiveTypeInfo(typeDescriptor);
        if (descriptiveTypeInfo != null) {
          return descriptiveTypeInfo;
        }
      }

      return null;
    };
  }

  private static <T> List<T> immutableServiceLoader(Class<T> type, ClassLoader classLoader) {
    return List.copyOf(StreamSupport.stream(ServiceLoader.load(type, classLoader).spliterator(), false).toList());
  }

  private record ProtoMethodMetadata(
    String serviceName,
    String methodName,
    String inputTypeName,
    String outputTypeName,
    boolean unary,
    List<String> declaredExceptions
  ) {
  }
}
