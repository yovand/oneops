/*******************************************************************************
 *
 *   Copyright 2015 Walmart, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *******************************************************************************/
package com.oneops.inductor;

import static com.oneops.cms.util.CmsConstants.MANAGED_VIA;
import static com.oneops.cms.util.CmsConstants.SECURED_BY;
import static com.oneops.inductor.InductorConstants.PRIVATE;
import static com.oneops.inductor.util.ResourceUtils.readResourceAsBytes;
import static com.oneops.inductor.util.ResourceUtils.readResourceAsString;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gson.Gson;
import com.oneops.cms.simple.domain.CmsWorkOrderSimple;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class InductorTest {

  private final Gson gson = new Gson();
  private static String remoteWo;
  private static String remoteAo;
  private static String localWo;
  private static Yaml yaml;

  @BeforeClass
  public static void init() {
    remoteWo = readResourceAsString("/remoteWorkOrder.json");
    localWo = readResourceAsString("/localWorkOrder.json");
    remoteAo = readResourceAsString("/remoteActionOrder.json");
    yaml = new Yaml();
  }

  @Test
  public void testProvider() {
    CmsWorkOrderSimple wo = gson.fromJson(remoteWo, CmsWorkOrderSimple.class);
    WorkOrderExecutor executor = new WorkOrderExecutor(mock(Config.class), mock(Semaphore.class));
    final String provider = executor.getProvider(wo);
    assertTrue(provider.equals("azure"));
  }

  @Test
  public void testKitchenPath() {
    CmsWorkOrderSimple wo = gson.fromJson(remoteWo, CmsWorkOrderSimple.class);
    Config cfg = new Config();
    cfg.setCircuitDir("/opt/oneops/inductor/packer");

    WorkOrderExecutor executor = new WorkOrderExecutor(cfg, mock(Semaphore.class));
    final String kitchenTestPath = executor.getKitchenTestPath(wo);
    assertTrue(
        kitchenTestPath.equals("/opt/oneops/inductor/circuit-oneops-1/components/cookbooks/user"));
    final String kitchenSpecPath = executor.getSpecFilePath(wo, kitchenTestPath);
    assertTrue(kitchenSpecPath.equals(
        "/opt/oneops/inductor/circuit-oneops-1/components/cookbooks/user/test/integration/add/serverspec/add_spec.rb"));
  }

  @Test
  public void testBomClass() {
    String bomPrefix = "bom\\.(.*\\.)*";
    String fqdnBomClass = bomPrefix + "Fqdn";
    assertTrue("bom.Fqdn".matches(fqdnBomClass));
    assertTrue("bom.oneops.1.Fqdn".matches(fqdnBomClass));
    assertTrue("bom.main.Fqdn".matches(fqdnBomClass));
    assertFalse("bomFqdn".matches(fqdnBomClass));
    assertFalse("bom.Compute".matches(fqdnBomClass));

    String ringBomClass = bomPrefix + "Ring";
    assertTrue("bom.Ring".matches(ringBomClass));
    assertTrue("bom.oneops.1.Ring".matches(ringBomClass));
    assertTrue("bom.main.Ring".matches(ringBomClass));
    assertFalse("bomRing".matches(ringBomClass));
    assertFalse("bom.Compute".matches(ringBomClass));

    String clusterBomClass = bomPrefix + "Cluster";
    assertTrue("bom.Cluster".matches(clusterBomClass));
    assertTrue("bom.oneops.1.Cluster".matches(clusterBomClass));
    assertTrue("bom.main.Cluster".matches(clusterBomClass));
    assertFalse("bomCluster".matches(clusterBomClass));
    assertFalse("bom.Compute".matches(clusterBomClass));
  }

  @Test
  public void testStatFile() throws Exception {
    String dataDir = "/opt/oneops/inductor/xxx/data";
    String logDir = "/opt/oneops/inductor/xxx/log";
    String statsLog = "inductor-stat.log";

    Config cfg = new Config();
    cfg.setDataDir(dataDir);
    StatCollector c = new StatCollector(cfg);
    c.setStatFileName(statsLog);
    assertEquals(c.getStatFileName(), Paths.get(logDir, statsLog).toString());

    statsLog = "/opt/inductor/log/test.log";
    c.setStatFileName(statsLog);
    assertEquals(c.getStatFileName(), statsLog);
  }

  @Test
  public void testEnvVars() {
    ProcessRunner p = new ProcessRunner(mock(Config.class));
    String remoteCmd[] = {"ssh", "-i"};
    assertNull(p.getEnvVars(remoteCmd, emptyMap()));

    String localCmd[] = new String[]{"chef-solo", "-i"};
    String envName = "WORKORDER";
    String envValue = "/tmp/wo.json";

    Map<String, String> extraVars = new HashMap<>();
    extraVars.put(envName, envValue);
    Map<String, String> envVars = p.getEnvVars(localCmd, extraVars);
    assertEquals(envValue, envVars.get(envName));

    localCmd = new String[]{"KITCHEN", "verify"};
    envVars = p.getEnvVars(localCmd, extraVars);
    assertEquals(envValue, envVars.get(envName));
  }

  @Test
  public void testRemoteKitchenConfig() {
    testKitchenConfig(remoteWo);
  }

  @Test
  public void testLocalKitchenConfig() {
    testKitchenConfig(localWo);
  }

  private void testKitchenConfig(String woString) {
    CmsWorkOrderSimple wo = gson.fromJson(woString, CmsWorkOrderSimple.class);
    Config cfg = new Config();
    cfg.setCircuitDir("/opt/oneops/inductor/packer");
    cfg.setIpAttribute("public_ip");
    cfg.setEnv("");
    cfg.init();

    WorkOrderExecutor executor = new WorkOrderExecutor(cfg, mock(Semaphore.class));
    String config = executor.generateKitchenConfig(wo, "/tmp/sshkey", "logkey");
    Object yamlConfig = yaml.load(config);
    assertNotNull("Invalid kitchen config.", yamlConfig);
  }

  // @Test
  public void getWorkOrderRsyncCommand() {
    CmsWorkOrderSimple wo = gson.fromJson(remoteWo, CmsWorkOrderSimple.class);
    Config cfg = new Config();
    cfg.setCircuitDir("/opt/oneops/inductor/packer");
    cfg.setIpAttribute("public_ip");
    cfg.setDataDir("/tmp/wos");

    WorkOrderExecutor executor = new WorkOrderExecutor(cfg, mock(Semaphore.class));
    final String[] cmdLine = executor.getRsyncCommandLineWo(wo, "sshkey");
    String rsync = "[/usr/bin/rsync, -az, --force, --exclude=*.png, --rsh=ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -p 22 -qi sshkey, --timeout=0, /tmp/wos/190494.json, oneops@inductor-test-host:/opt/oneops/workorder/user.test_wo-25392-1.json]";
    Assert.assertEquals(Arrays.toString(cmdLine), rsync);
  }

  /**
   * This could be used for local testing, Need to add key and modify the user-app.json accordingly
   */
  //@Test
  public void runVerification() throws IOException {
    CmsWorkOrderSimple wo = gson.fromJson(remoteWo, CmsWorkOrderSimple.class);
    Config cfg = new Config();
    cfg.setCircuitDir("/opt/oneops/inductor/packer");
    cfg.setIpAttribute("public_ip");
    cfg.setDataDir("/tmp/wos");
    cfg.setVerifyMode(true);
    cfg.setClouds(Collections.EMPTY_LIST);

    String privKey = readResourceAsString("/verification/key");
    wo.getPayLoadEntryAt(SECURED_BY, 0).getCiAttributes().put(PRIVATE, privKey);
    wo.getPayLoad().get(MANAGED_VIA).get(0)
        .setCiAttributes(Collections.singletonMap("public_ip", ""));
    wo.getRfcCi().setCiName("app-7401500-1");
    WorkOrderExecutor executor = new WorkOrderExecutor(cfg, mock(Semaphore.class));
    HashMap<String, CmsWorkOrderSimple> hm = new HashMap<>();
    hm.put("workorder", wo);
    byte[] userWO = readResourceAsBytes("user-app.json");

    Files.write(Paths.get("/tmp/wos/190494.json"), userWO, TRUNCATE_EXISTING);
    Map<String, String> mp = new HashMap<>();
    executor.runVerification(wo, mp);
  }

}
