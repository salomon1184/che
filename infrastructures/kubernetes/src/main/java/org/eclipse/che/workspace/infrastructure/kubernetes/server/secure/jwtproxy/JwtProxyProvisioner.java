/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.kubernetes.server.secure.jwtproxy;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.eclipse.che.commons.lang.NameGenerator.generate;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.CHE_ORIGINAL_NAME_LABEL;
import static org.eclipse.che.workspace.infrastructure.kubernetes.server.KubernetesServerExposer.SERVER_PREFIX;
import static org.eclipse.che.workspace.infrastructure.kubernetes.server.KubernetesServerExposer.SERVER_UNIQUE_PART_SIZE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.che.api.core.model.workspace.config.MachineConfig;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.InternalInfrastructureException;
import org.eclipse.che.api.workspace.server.spi.environment.InternalMachineConfig;
import org.eclipse.che.multiuser.machine.authentication.server.signature.SignatureKeyManager;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.KubernetesEnvironment;
import org.eclipse.che.workspace.infrastructure.kubernetes.server.ServerServiceBuilder;

/** @author Sergii Leshchenko */
public class JwtProxyProvisioner {

  static final int FIRST_AVAILABLE_PORT = 4400;

  static final int JWT_PROXY_MEMORY_LIMIT_BYTES = 128 * 1024 * 1024; // 128mb

  static final String PUBLIC_KEY_HEADER = "-----BEGIN PUBLIC KEY-----\n";
  static final String PUBLIC_KEY_FOOTER = "\n-----END PUBLIC KEY-----";

  static final String JWTPROXY_IMAGE = "ksmster/jwtproxy";
  static final String JWT_PROXY_CONFIG_FILE = "config.yaml";
  static final String JWT_PROXY_MACHINE_NAME = "jwtproxy";

  static final String JWT_PROXY_CONFIG_FOLDER = "/config";
  static final String JWT_PROXY_PUBLIC_KEY_FILE = "mykey.pub";

  private final SignatureKeyManager signatureKeyManager;

  private final RuntimeIdentity identity;

  private final JwtProxyConfigBuilder proxyConfigBuilder;

  private final String serviceName;
  private int availablePort;

  public JwtProxyProvisioner(RuntimeIdentity identity, SignatureKeyManager signatureKeyManager) {
    this.signatureKeyManager = signatureKeyManager;

    this.identity = identity;

    this.proxyConfigBuilder = new JwtProxyConfigBuilder(identity.getWorkspaceId());

    this.serviceName = generate(SERVER_PREFIX, SERVER_UNIQUE_PART_SIZE) + "-jwtproxy";
    this.availablePort = FIRST_AVAILABLE_PORT;
  }

  /**
   * Modifies Kubernetes environment to expose the specified service port via JWTProxy.
   *
   * @param k8sEnv Kubernetes environment to modify
   * @param backendServiceName service name that will be exposed
   * @param backendServicePort service port that will be exposed
   * @param protocol protocol that will be used for exposed port
   * @return JWTProxy service port that expose the specified one
   * @throws InfrastructureException if any exception occurs during port exposing
   */
  public ServicePort expose(
      KubernetesEnvironment k8sEnv,
      String backendServiceName,
      int backendServicePort,
      String protocol)
      throws InfrastructureException {
    ensureJwtProxyInjected(k8sEnv);

    int listenPort = availablePort++;

    proxyConfigBuilder.addVerifierProxy(
        listenPort, "http://" + backendServiceName + ":" + backendServicePort);
    k8sEnv
        .getConfigMaps()
        .get(getConfigMapName())
        .getData()
        .put(JWT_PROXY_CONFIG_FILE, proxyConfigBuilder.build());

    ServicePort exposedPort =
        new ServicePortBuilder()
            .withName(backendServiceName + "-" + listenPort)
            .withPort(listenPort)
            .withProtocol(protocol)
            .withNewTargetPort(listenPort)
            .build();

    k8sEnv.getServices().get(getServiceName()).getSpec().getPorts().add(exposedPort);

    return exposedPort;
  }

  /** Returns service name that exposed JWTProxy Pod. */
  public String getServiceName() {
    return serviceName;
  }

  /** Returns config map name that will be mounted into JWTProxy Pod. */
  @VisibleForTesting
  String getConfigMapName() {
    return "jwtproxy-config-" + identity.getWorkspaceId();
  }

  private void ensureJwtProxyInjected(KubernetesEnvironment k8sEnv) throws InfrastructureException {
    if (!k8sEnv.getMachines().containsKey(JWT_PROXY_MACHINE_NAME)) {
      k8sEnv.getMachines().put(JWT_PROXY_MACHINE_NAME, createJwtProxyMachine());
      k8sEnv.getPods().put("jwtproxy", createJwtProxyPod(identity));

      KeyPair keyPair = signatureKeyManager.getKeyPair();
      if (keyPair == null) {
        throw new InternalInfrastructureException(
            "Key pair for machine authentication does not exist");
      }
      Map<String, String> initConfigMapData = new HashMap<>();
      initConfigMapData.put(
          JWT_PROXY_PUBLIC_KEY_FILE,
          PUBLIC_KEY_HEADER
              + java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
              + PUBLIC_KEY_FOOTER);

      initConfigMapData.put(JWT_PROXY_CONFIG_FILE, proxyConfigBuilder.build());

      ConfigMap jwtProxyConfigMap =
          new ConfigMapBuilder()
              .withNewMetadata()
              .withName(getConfigMapName())
              .endMetadata()
              .withData(initConfigMapData)
              .build();
      k8sEnv.getConfigMaps().put(jwtProxyConfigMap.getMetadata().getName(), jwtProxyConfigMap);

      Service jwtProxyService =
          new ServerServiceBuilder()
              .withName(serviceName)
              .withSelectorEntry(CHE_ORIGINAL_NAME_LABEL, JWT_PROXY_MACHINE_NAME)
              .withMachineName(JWT_PROXY_MACHINE_NAME)
              .withPorts(emptyList())
              .build();
      k8sEnv.getServices().put(jwtProxyService.getMetadata().getName(), jwtProxyService);
    }
  }

  private InternalMachineConfig createJwtProxyMachine() {
    return new InternalMachineConfig(
        null,
        emptyMap(),
        emptyMap(),
        ImmutableMap.of(
            MachineConfig.MEMORY_LIMIT_ATTRIBUTE, Integer.toString(JWT_PROXY_MEMORY_LIMIT_BYTES)),
        null);
  }

  private Pod createJwtProxyPod(RuntimeIdentity identity) {
    return new PodBuilder()
        .withNewMetadata()
        .withName("jwtproxy")
        .withAnnotations(
            ImmutableMap.of(
                "org.eclipse.che.container.verifier.machine_name", JWT_PROXY_MACHINE_NAME))
        .endMetadata()
        .withNewSpec()
        .withContainers(
            new ContainerBuilder()
                .withName("verifier")
                .withImage(JWTPROXY_IMAGE)
                .withVolumeMounts(
                    new VolumeMount(
                        JWT_PROXY_CONFIG_FOLDER + "/", "jwtproxy-config-volume", false, null))
                .withArgs("-config", JWT_PROXY_CONFIG_FOLDER + "/" + JWT_PROXY_CONFIG_FILE)
                .build())
        .withVolumes(
            new VolumeBuilder()
                .withName("jwtproxy-config-volume")
                .withNewConfigMap()
                .withName("jwtproxy-config-" + identity.getWorkspaceId())
                .endConfigMap()
                .build())
        .endSpec()
        .build();
  }
}
