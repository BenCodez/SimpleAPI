package com.bencodez.simpleapi.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class BuildInputPinningTest {

	@Test
	void documentationUsesVerifiedReleaseCommitAndAggregateOutput() throws IOException {
		String workflow = Files.readString(Path.of("..", ".github", "workflows", "publish-javadoc.yml"));
		String verificationJob = job(workflow, "verify-release");
		String buildJob = job(workflow, "build");
		String deployJob = job(workflow, "deploy");

		assertTrue(workflow.contains("types: [published, released]"));
		assertTrue(verificationJob.contains("git merge-base --is-ancestor \"$tag_commit\" origin/main"));
		assertFalse(workflow.contains("target_commitish"));
		assertTrue(verificationJob.contains("FETCH_HEAD^{commit}"));
		assertFalse(verificationJob.contains("refs/tags/release"));
		assertTrue(buildJob.contains("ref: ${{ needs.verify-release.outputs.commit }}"));
		assertTrue(buildJob.contains("path: SimpleAPI/target/reports/apidocs"));
		assertTrue(deployJob.contains("needs.build.result == 'success'"));
	}

	@Test
	void ordinaryBuildHasNoWriteScopedDependencySubmission() throws IOException {
		String workflow = Files.readString(Path.of("..", ".github", "workflows", "maven.yml"));

		assertTrue(Pattern.compile("(?m)^permissions:\\R  contents: read$").matcher(workflow).find());
		assertFalse(workflow.contains("contents: write"));
		assertFalse(workflow.contains("maven-dependency-submission-action"));
	}

	private static String job(String workflow, String name) {
		Matcher matcher = Pattern.compile("(?ms)^  " + Pattern.quote(name)
				+ ":\\R(?<job>.*?)(?=^  [A-Za-z0-9_-]+:\\R|\\z)").matcher(workflow);
		assertTrue(matcher.find(), () -> "Missing " + name + " job");
		return matcher.group("job");
	}
}
