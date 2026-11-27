# Manual issue investigation

The manual workflow calls the shared VotingPlugin investigator at the immutable
revision recorded in `../workflows/investigate-issue.yml`. This repository does
not contain a second investigation engine.

The workflow has no automatic trigger and never posts a public issue comment.
It produces an unreviewed report artifact; source evidence does not establish a
deployed build, a verified fix, or runtime acceptance. The shared workflow checks
the caller repository, default branch, run attempt, issue, engine revision and
artifact identity before admitting work. Reruns cannot reopen a billable attempt.

## Prerequisites before an authorized manual run

- A repository secret named `OPENAI_API_KEY` and the intended repository variable
  `OPENAI_PROJECT_ID`. There is no fallback project identity or inherited secret
  set. Environment-only secrets are not passed by this caller.
- The approved shared disclosure policy URL and exact digest, configured through
  `ISSUE_INVESTIGATION_POLICY_URL` and `ISSUE_INVESTIGATION_DISCLOSURE_SHA256`.
- Verified hosted runner/API access, artifact provenance, cleanup, and permission
  to incur the run's cost. The runtime input is not a monetary spending cap.
- An existing SimpleAPI issue, with the investigation dispatched from `main`.
  The shared admission step rejects unsupported identities or inputs.

These are requirements, not a statement that configuration has been installed or
tested. Repository settings, secret values and project identity are not supplied
by this change. No hosted smoke test accompanies it.

## Local checks

```sh
python3 -m unittest discover -s .github/issue-investigation/tests -v
actionlint .github/workflows/investigate-issue.yml
```

Caller tests check the immutable reference, manual-only boundary, read-only
permissions, explicit secret mapping and input contract. The central engine owns
event admission, version/evidence collection, budget, deduplication, artifact and
posting tests. Validate changes to that engine at the selected revision rather
than copying its implementation here.

## Activation and rollback

Publishing or reviewing this source change does not authorize a manual run,
configure secrets, or enable automatic investigation or posting. Verify the
prerequisites and obtain the applicable runtime authority before dispatching.
Automatic posting would require a separately reviewed change and authorization;
there is no posting input on this workflow.

To roll back the entrypoint, revert the commit adding this workflow and its local
checks/documentation. Preserve the shared engine, existing Maven/Javadoc
workflows, repository settings and `OPENAI_API_KEY`. Handle any already-running
session through its existing controller; deleting the caller does not clean it up.
