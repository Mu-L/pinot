/**
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
package org.apache.pinot.spi.env;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;


public class CommonsConfigurationUtilsTest {
  private static final File TEMP_DIR = new File(FileUtils.getTempDirectory(), "CommonsConfigurationUtilsTest");
  private static final File CONFIG_FILE = new File(TEMP_DIR, "config");
  private static final File SEGMENT_METADATA_CONFIG_FILE = new File(TEMP_DIR, "segmentMetadataConfig");
  private static final String PROPERTY_KEY = "testKey";
  private static final int NUM_ROUNDS = 10000;

  @BeforeClass
  public void setUp()
      throws IOException {
    FileUtils.deleteDirectory(TEMP_DIR);
  }

  @AfterClass
  public void tearDown()
      throws IOException {
    FileUtils.deleteDirectory(TEMP_DIR);
  }

  @Test
  public void testSegmentMetadataFromFile() {
    // load the existing config and check the properties config instance
    try {
      PropertiesConfiguration config = CommonsConfigurationUtils
          .getSegmentMetadataFromFile(SEGMENT_METADATA_CONFIG_FILE, true);
      assertNotNull(config);

      config.setProperty("testKey", "testValue");

      // add the segment version header to the file and read it again
      CommonsConfigurationUtils.saveSegmentMetadataToFile(config, CONFIG_FILE,
          CommonsConfigurationUtils.PROPERTIES_CONFIGURATION_HEADER_VERSION_2);

      // reading the property with header.
      config = CommonsConfigurationUtils.getSegmentMetadataFromFile(CONFIG_FILE, true);
      assertNotNull(config);
      assertEquals(config.getHeader(), "# version = 2");
    } catch (Exception ex) {
      Assert.fail("should not throw ConfigurationException exception with valid file, " + ex.getMessage());
    }

    // load the non-existing file and expect the exception
    Assert.expectThrows(NullPointerException.class,
        () -> CommonsConfigurationUtils.getSegmentMetadataFromFile(null, true));
  }

  @Test
  public void testPropertyValueWithSpecialCharacters()
      throws Exception {
    // Leading/trailing whitespace
    testPropertyValueWithSpecialCharacters(" a");
    testPropertyValueWithSpecialCharacters("a ");
    testPropertyValueWithSpecialCharacters(" a ");
    testPropertyValueWithSpecialCharacters("  a  ");
    testPropertyValueWithSpecialCharacters(" a\t");
    testPropertyValueWithSpecialCharacters("\na ");

    // Whitespace in the middle
    testPropertyValueWithSpecialCharacters("a\t b");
    testPropertyValueWithSpecialCharacters("a \nb");

    // List separator (comma)
    testPropertyValueWithSpecialCharacters("a,b,c");
    testPropertyValueWithSpecialCharacters(",a b");
    testPropertyValueWithSpecialCharacters(",a,,,b,,c,");

    // List separator with backslashes
    testPropertyValueWithSpecialCharacters("a\\,b,\\c");
    testPropertyValueWithSpecialCharacters("\\a\\,,b\\, \\c");
    testPropertyValueWithSpecialCharacters("a\\\\,, ,b");
    testPropertyValueWithSpecialCharacters("a\\\\\\,b");

    // Empty string
    testPropertyValueWithSpecialCharacters("");

    // Variable substitution (should be disabled)
    testPropertyValueWithSpecialCharacters("$${testKey}");

    // Escape character for variable substitution
    testPropertyValueWithSpecialCharacters("$${");

    for (int i = 0; i < NUM_ROUNDS; i++) {
      testPropertyValueWithSpecialCharacters(RandomStringUtils.randomAscii(5));
      testPropertyValueWithSpecialCharacters(StringUtils.remove(RandomStringUtils.random(5), '\0'));
    }
  }

  private void testPropertyValueWithSpecialCharacters(String value)
      throws Exception {
    String replacedValue = CommonsConfigurationUtils.replaceSpecialCharacterInPropertyValue(value);
    if (replacedValue == null) {
      boolean hasSurrogate = false;
      int length = value.length();
      for (int i = 0; i < length; i++) {
        if (Character.isSurrogate(value.charAt(i))) {
          hasSurrogate = true;
          break;
        }
      }
      assertTrue(hasSurrogate);
      return;
    }

    PropertiesConfiguration configuration = CommonsConfigurationUtils.fromFile(CONFIG_FILE, true,
        PropertyIOFactoryKind.DefaultIOFactory);
    configuration.setProperty(PROPERTY_KEY, replacedValue);
    String recoveredValue = CommonsConfigurationUtils.recoverSpecialCharacterInPropertyValue(
        (String) configuration.getProperty(PROPERTY_KEY));
    assertEquals(recoveredValue, value);

    CommonsConfigurationUtils.saveToFile(configuration, CONFIG_FILE);
    configuration = CommonsConfigurationUtils.fromFile(CONFIG_FILE, true,
        PropertyIOFactoryKind.DefaultIOFactory);
    recoveredValue = CommonsConfigurationUtils.recoverSpecialCharacterInPropertyValue(
        (String) configuration.getProperty(PROPERTY_KEY));
    assertEquals(recoveredValue, value);
  }

  @Test
  public void testPropertiesConfigurationFromFile()
      throws ConfigurationException {
    PropertiesConfiguration configuration = CommonsConfigurationUtils.fromFile(null, false, null);
    assertNotNull(configuration);
    configuration.setProperty("Test Key", "Test Value");
    CommonsConfigurationUtils.saveToFile(configuration, CONFIG_FILE);

    configuration = CommonsConfigurationUtils.fromFile(CONFIG_FILE, false, null);
    assertNotNull(configuration);
    assertEquals(configuration.getProperty("Test Key"), "Test Value");
  }

  @Test
  public void testPropertiesConfigurationFromPath()
      throws ConfigurationException {
    PropertiesConfiguration configuration = CommonsConfigurationUtils.fromPath(null, false, null);
    assertNotNull(configuration);
    configuration.setProperty("Test Key", "Test Value");
    CommonsConfigurationUtils.saveToFile(configuration, CONFIG_FILE);

    configuration = CommonsConfigurationUtils.fromPath(CONFIG_FILE.getPath(), false, null);
    assertNotNull(configuration);
    assertEquals(configuration.getProperty("Test Key"), "Test Value");
  }

  @Test
  public void testPropertiesConfigurationFromInputStream()
      throws ConfigurationException, FileNotFoundException {
    PropertiesConfiguration configuration = CommonsConfigurationUtils.fromInputStream(null, false, null);
    assertNotNull(configuration);
    configuration.setProperty("Test Key", "Test Value");
    CommonsConfigurationUtils.saveToFile(configuration, CONFIG_FILE);

    FileInputStream inputStream = new FileInputStream(CONFIG_FILE);
    configuration = CommonsConfigurationUtils.fromInputStream(inputStream, false, null);
    assertNotNull(configuration);
    assertEquals(configuration.getProperty("Test Key"), "Test Value");
  }
}
