# ClicTest Action (GitHub Actions + Gitea Actions)

Runs ClicTest test suites from a workflow, the same way the ClicTest Jenkins plugin does:
authenticate (`/getuser`), trigger one execution per test suite x browser (`/execute`),
poll `/executionStatus` until `COMPLETED` / `FAILED` / `TERMINATED`, then fail the job if any execution failed.

No build step: `ClicTest.java` runs through the JDK source launcher (`java ClicTest.java`), so the runner only needs a JDK (installed by `actions/setup-java`).

## Prerequisites

- The ClicTest service URL must be reachable from the runner. GitHub-hosted runners need a public endpoint.
- Repository secrets `CLICTEST_USER` and `CLICTEST_PASSWORD`.
- Local-browser runs (`client-ip`) need a self-hosted runner on a machine with the ClicTest agent.
- Gitea: Actions enabled (`[actions] ENABLED=true` in `app.ini`) and an `act_runner` registered. The runner image needs `bash`. The default `node` images have it, but not Java, so keep `java-version` set.

## Usage

GitHub:

```yaml
name: ClicTest
on: [push, workflow_dispatch]

jobs:
  clictest:
    runs-on: ubuntu-latest
    steps:
      - id: clictest
        uses: masterclic/clictest@v1
        with:
          env-name: preprodone
          project-name: Sugarcrm
          username: ${{ secrets.CLICTEST_USER }}
          password: ${{ secrets.CLICTEST_PASSWORD }}
          test-suites: |
            [
              {"testSuiteName": "Wallmart_Test", "testSuiteVersion": "1",
               "url": "https://example.com", "platform": "windows10",
               "browsers": ["chrome", "firefox"]}
            ]
      - run: echo "Report ${{ steps.clictest.outputs.html-report-url }}"
        if: always()
```

Gitea: same workflow under `.gitea/workflows/` (or `.github/workflows/`), but reference the action by full URL, because a bare `owner/repo` resolves against github.com by default:

```yaml
        uses: http://gitea.smartintelectual.com:3000/smartintelectual/clictest-action@v1
```

## GitHub setup

Prerequisites:

- Actions enabled for the test repository (Settings > Actions > General: allow all actions, or allow `masterclic/clictest@*`).
- ClicTest service reachable from GitHub-hosted runners over HTTPS:
  `https://preprod.clictest.com:8443/ClicTestJenkinsService/rest/jenkinsServices`. A private-network service needs a self-hosted runner.
- For the Configurator page: CORS on the service (Tomcat `org.apache.catalina.filters.CorsFilter` in the service `web.xml`,
  `cors.allowed.origins` = `*`, `cors.allowed.methods` = `GET,OPTIONS`).
- A ClicTest user for the workflow, and a GitHub personal access token for Run now.

Action repository (once):

1. `masterclic/clictest` is tagged `v1.0.0` / `v1` (see Releasing).
2. Settings > Pages > Deploy from a branch: `master`, folder `/docs`. Configurator: `https://masterclic.github.io/clictest/`.

Each test repository:

1. Copy `examples/clictest-run.yml` to `.github/workflows/clictest-run.yml` and commit it to the branch you run from.
2. Optional: Settings > Secrets and variables > Actions > New repository secret: `CLICTEST_USER`, `CLICTEST_PASSWORD`.
   Needed only for runs that have no Configurator user (Actions tab with blank fields, or your own automatic triggers).
3. Check **ClicTest Run** shows under the Actions tab.

Access token for Run now:

- Fine-grained: Settings > Developer settings > Personal access tokens > Fine-grained tokens. Repository access: the test repositories.
  Repository permissions: **Actions: Read and write** (Metadata: Read is added automatically).
- Classic: `repo` scope.

Run now fields: API base `https://api.github.com`, Repository `owner/name`, Workflow file `clictest-run.yml`,
Branch that holds the workflow (e.g. `master`), Access token.

| Error | Cause and fix |
|---|---|
| Cannot reach ... must allow cross-origin requests | `http://` service URL or no CORS headers: use the HTTPS URL, enable the CorsFilter |
| HTTP 404 Not Found | Workflow file missing in that repository, wrong repository, or wrong branch (`main` vs `master`) |
| HTTP 403 Resource not accessible by personal access token | Token lacks Actions write, or repository not selected on the fine-grained token |
| HTTP 422 Unexpected inputs | Workflow file predates the `username` / `password` inputs: copy `examples/clictest-run.yml` again |
| Missing required input CLICTEST_USERNAME | Started without a Configurator user and the secrets are not set |
| Run fails at sign-in | Configurator username / password wrong, or `CLICTEST_USER` / `CLICTEST_PASSWORD` secrets wrong |

## Self-hosted runner

Use one when the ClicTest service or the browsers are on a private network (for example `Clictest-Windows-Runner-01`).

1. Register the runner on the test repository (Settings > Actions > Runners > New self-hosted runner) and run it as a service.
   It must show **Idle** there. A runner registered on another repository or organization is not visible to this one.
2. In the workflow, replace `runs-on: ubuntu-latest` with the runner's labels:
   ```yaml
   runs-on: [self-hosted, Windows]
   ```
   To pin one machine, add a custom label to it (Runners > the runner > Labels) and use `[self-hosted, <label>]`.
3. Windows runners need nothing extra: the action runs Java through `cmd` (Linux and macOS use `bash`).
   Java: `actions/setup-java` downloads a JDK, so the runner needs internet access; otherwise install JDK 17+ and set `java-version: ''`.

Self-hosted runners execute workflow code from the repository. GitHub advises against using them on public repositories:
a fork pull request can bring its own workflow that runs on your machine. Prefer a private test repository, or at least
set Settings > Actions > General > "Require approval for all outside collaborators".

## Configurator UI (GitHub Pages)

`docs/index.html` is a single-page configurator, the GitHub counterpart of the ClicTest Azure DevOps hub:
cascading dropdowns (environment, project, test suite, version, platform, URL, browsers), multiple test suites,
then **Copy** the `test-suites` JSON / workflow step, or **Run now** to start the `ClicTest Run` workflow.

Setup:

1. Repository settings > Pages > Deploy from branch `master`, folder `/docs`. The page is served at
   `https://masterclic.github.io/clictest/`. Gitea has no Pages: host `docs/index.html` on any static web server.
2. In each repository that runs tests, add `examples/clictest-run.yml` as `.github/workflows/clictest-run.yml`.
   Run now starts the workflow as the ClicTest user signed in on the page, so each person uses their own account.
3. Run now needs an access token with Actions write permission on that repository
   (GitHub fine-grained token: "Actions: Read and write"; Gitea: token with `write:repository`).
   The token stays in the browser tab and goes only to the API base you enter.

ClicTest server requirements for the page:

- **HTTPS.** The page is served over HTTPS, so browsers block calls to an `http://` service URL (mixed content).
- **CORS.** The service must answer with `Access-Control-Allow-Origin` (preprod: `*`, via Tomcat's CorsFilter).

Lookup calls used: `/envdetails`, `/projectdetails`, `/testsuitedetails`, `/configurationdetails`,
`/testsuiteversion`, `/browserversion` (same as the Jenkins plugin's dropdowns).

## Inputs

| Input | Required | Default | Notes |
|---|---|---|---|
| `env-name` | yes | | ClicTest environment |
| `project-name` | yes | | ClicTest project |
| `username` | yes | | use a secret |
| `password` | yes | | use a secret |
| `test-suites` | yes | | JSON array: `testSuiteName`, `testSuiteVersion`, `url`, `platform`, `browsers[]` |
| `service-url` | no | preprod service URL | same as `serviceurl` in the plugin's `config.properties` |
| `job-name` | no | `GITHUB_REPOSITORY` | sent as `jobName`; build number is `GITHUB_RUN_NUMBER` |
| `client-ip` | no | empty | local ClicTest agent IP |
| `max-parallel` | no | `10` | |
| `poll-interval-seconds` | no | `10` | |
| `timeout-minutes` | no | `60` | per execution |
| `java-version` | no | `17` | `''` skips setup-java |

## Outputs

`result` (`success` / `failure`), `html-report-url`, `pdf-report-url`. A results table is also written to the job summary.

## Local check

```bash
java ClicTest.java --self-test
```

## Security note

`/getuser` takes the password as a query parameter, so it can show up in ClicTest server and proxy access logs. Use HTTPS for `service-url`, and move the server to POST or token auth when possible.

Run now sends the password as a `workflow_dispatch` input. The action reads it from the event file and masks it before any request.
Never pass `${{ inputs.password }}` to a step yourself: the runner prints `with:` and `env:` values in the log before any mask applies.
Inputs are not encrypted like secrets: anyone with write access to the repository can change the workflow and read the password of the next person who runs it. Limit write access, and prefer private repositories.

## Releasing

`action.yml` sits at the repository root, so workflows reference `masterclic/clictest@<tag>`.
Tag a release and move the major tag:

```bash
git tag v1.0.0 && git tag -f v1 && git push origin v1.0.0 && git push -f origin v1
```

Related project: ClicTest Jenkins plugin (same REST flow, see `ClictestCIPublisher`).
