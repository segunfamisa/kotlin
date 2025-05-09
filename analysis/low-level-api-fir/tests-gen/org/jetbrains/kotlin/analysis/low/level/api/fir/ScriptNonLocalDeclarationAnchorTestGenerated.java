/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.analysis.api.GenerateAnalysisApiTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors")
@TestDataPath("$PROJECT_ROOT")
public class ScriptNonLocalDeclarationAnchorTestGenerated extends AbstractScriptNonLocalDeclarationAnchorTest {
  @Test
  public void testAllFilesPresentInNonLocalDeclarationAnchors() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors"), Pattern.compile("^(.+)\\.(kts)$"), null, true);
  }

  @Test
  @TestMetadata("classWithMembersScript.kts")
  public void testClassWithMembersScript() {
    runTest("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors/classWithMembersScript.kts");
  }

  @Test
  @TestMetadata("classWithMembersWithPackage.kts")
  public void testClassWithMembersWithPackage() {
    runTest("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors/classWithMembersWithPackage.kts");
  }

  @Test
  @TestMetadata("contextParametersScript.kts")
  public void testContextParametersScript() {
    runTest("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors/contextParametersScript.kts");
  }

  @Test
  @TestMetadata("destructuringDeclarationsScript.kts")
  public void testDestructuringDeclarationsScript() {
    runTest("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors/destructuringDeclarationsScript.kts");
  }

  @Test
  @TestMetadata("statements.kts")
  public void testStatements() {
    runTest("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors/statements.kts");
  }

  @Test
  @TestMetadata("topLevelFor.kts")
  public void testTopLevelFor() {
    runTest("analysis/low-level-api-fir/testData/nonLocalDeclarationAnchors/topLevelFor.kts");
  }
}
