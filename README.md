# MapRoulette API
[![Build Status](https://travis-ci.org/maproulette/maproulette2.svg?branch=dev)](https://travis-ci.org/maproulette/maproulette2)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=maproulette_maproulette2&metric=alert_status)](https://sonarcloud.io/dashboard?id=maproulette_maproulette2)
![GitHub release (latest by date)](https://img.shields.io/github/v/release/maproulette/maproulette2)

Welcome to the repository for the MapRoulette back-end server code. The MapRoulette back-end exposes the MapRoulette API, which the MapRoulette front-end web application depends on. The source code for the web application is in [a separate repository](https://github.com/osmlab/maproulette3).

**If you just want to deploy the MapRoulette back-end, [we have a 🚢 Docker image 🚢 for that](https://github.com/maproulette/maproulette2-docker)**. This is especially useful if you want to contribute to the MapRoulette front-end and don't intend to touch the back-end.

## Requirements

MapRoulette depends on several technologies for building and running the project. Newer versions may work but are untested.

* Java 11 SDK
* PostgreSQL 11.x with PostGIS 2.5.x
* [Scala Build Tool](https://www.scala-sbt.org/download.html) 1.7.2

## Setup

MapRoulette is a complex tool and depends on several other tools
([Mapillary](https://www.mapillary.com/),
[Overpass API](https://overpass-api.de/),
[OpenStreetMap](https://www.openstreetmap.org/),
PostgreSQL, and others) that need to be correctly configured within the application's
[HOCON configuration](https://github.com/lightbend/config/blob/main/HOCON.md) files.

The initial setup is not trivial, so the documentation is split to specific steps where each step can be easily validated.
The validation at each step is a helpful sanity check so please, regardless of your experience, **take the time to verify along the way**.

---

### Step 1: Installing Tools

To get started you'll need to install Docker, JDK 11, and sbt.

#### Docker

Docker is a virtualization tool and feel free to use
[Docker Desktop](https://docs.docker.com/get-docker/),
or [Podman](https://podman.io/),
or even [Rancher Desktop](https://rancherdesktop.io/).

#### JDK 11 and sbt

[sdkman](https://sdkman.io/install) is a great tool to install a specific build of the JDK to keep your environment
as similar to production as possible. It also handles fetching x8664 and aarch64 builds automatically.
Follow the installation steps and install the JDK and sbt using a command similar to:

* `sdk install java 11.0.17-tem`
* `sdk install sbt 1.8.0`

#### Validation

Within a terminal check that docker, javac, and sbt are working.

* `docker run hello-world`
  * Verify docker works
* `javac -version`
  * Verify javac is JDK 11
  * Apple Silicon: Verify that the JDK is an aarch64 build and not x8664.
* `sbt -version`

---

### Step 2: Setup PostGIS and MapRoulette DB Configuration

#### PostGIS Container

MapRoulette development assumes a database is running on the local system within a docker container.

Below is a sample command to run a PostGIS database within a container and sets necessary ports/credentials.

* **NOTE: Apple Silicon:** Use `ghcr.io/baosystems/postgis:11-3.3` docker image since postGIS does not yet publish aarch64 images.
* NOTE: No volume mount is used so the database's data will be deleted when the container is deleted.
  If you'd like to keep the data external of the container, be sure to add `--volume "/some/path/here/postgres-data":/var/lib/postgresql/data` to the docker call.

```sh
docker run \
    -d \
    -p 5432:5432 \
    --name maproulette-postgis \
    --restart unless-stopped \
    --shm-size=512MB \
    -e POSTGRES_DB=maproulette-db \
    -e POSTGRES_USER=maproulette-db-user \
    -e POSTGRES_PASSWORD=maproulette-db-pass \
    postgis/postgis:11-3.3
```

* NOTE: If there's a port conflict, you probably have another pg instance running. Check with `docker ps`.
* NOTE: It is helpful to see logs with `docker logs -f maproulette-postgis`
* NOTE: To stop the container, that'd be `docker stop maproulette-postgis`. Then you can start it again using `docker start maproulette-postgis`.

#### MapRoulette Database Configuration

Clone the maproulette2 repository and `cd` to that directory, and create `conf/dev.conf` using the example file:

```sh
    cp conf/dev.conf.example conf/dev.conf
```

Edit `conf/dev.conf` and set db.default based on the previous step's POSTGRES_DB, POSTGRES_USER,
and POSTGRES_PASSWORD values:

```text
db.default {
  url="jdbc:postgresql://localhost:5432/maproulette-db"
  username="maproulette-db-user"
  password="maproulette-db-pass"
}
```

Now start the MapRoulette server! Run this command in a terminal, **not within Intellij/vscode**:

`sbt -J-Xms4G -J-Xmx4G -J-Dconfig.file=./conf/dev.conf -J-Dlogger.resource=logback-dev.xml run`

There should be some output that looks like this:

    --- (Running the application, auto-reloading is enabled) ---
    [info] p.c.s.AkkaHttpServer - Listening for HTTP on /0:0:0:0:0:0:0:0:9000
    (Server started, use Enter to stop and go back to the console...)

That's the expected output. And when you need, like it says, use Enter to stop the server.

#### Step 2 - Validation

Open a new terminal so that the MapRoulette server is not stopped. Verify:

* Check that the database is running, `docker inspect -f '{{.State.Running}}' maproulette-postgis`
* Start the MapRoulette server if it's not already running (see the previous section for the `sbt` command)
* Verify that the local [Swagger docs load in a web browser](http://127.0.0.1:9000/docs/)
* Do this GET call to get the list of challenges: `curl http://127.0.0.1:9000/api/v2/challenges`
  * The result may be an empty array or some data, as long as it's not a failed call
  * Tip: the response can be formatted by piping to `jq`
* Open the terminal that has `sbt` running the server. Verify that there are log messages printed.

If there's a failure at this point _READ THE LOG MESSAGES_, checking for simple setup issues.

A few known setup misconfigurations could be:

* Does the server log that it can't communicate with the database? "HikariPool Connection is not available, request timed out after 30000ms"
  * Check the `conf/dev.conf` MapRoulette server configuration file for typos, accidental setting overrides, etc
  * Check that the MapRoulette server conf and postgis are using the same database, user, password.
* Recursive loading of Guice injected object: 'java.lang.IllegalStateException: Recursive load of: org.maproulette.framework.service.ServiceManager.<init>()'
  * We've seen this with Apple Silicon aarch64 systems. Possibly related to mixing aarch64 and x8664 JDKs. Intellij 2022.3 pre-release seems to work fine
* Having some other issue?
  * Please file a GitHub issue with a detailed description on what was tried and include the error message.

---

### Step 3: Setup OAuth Login and Run the front-end GUI

With the database and MapRoulette server working together, it's now time to get the front-end login workflow working!

High level the user visits to MapRoulette, clicks 'login' and is sent to OpenStreepMap to login then, after authentication, MapRoulette is able to make OSM changes of behalf
of the user. This is done by registering the local dev instance of MapRoulette as an OSM OAuth Application to create keys, and then
the those keys are pasted into the MapRoulette server configuration. More details on how OSM uses OAuth is on
the [OpenStreetMap OAuth documentation](https://wiki.openstreetmap.org/wiki/OAuth).

#### Register localhost as an OpenStreetMap OAuth Application

Within OpenStreetMap, MapRoulette needs to be setup as an "OAuth Application" so that changes in MapRoulette are associated
with a user's OSM account.

1. Login to the [development OpenStreetMap server](https://master.apis.dev.openstreetmap.org) (make an account if needed)
1. In the top right, click the user and go to `My Settings`
1. Then click `OAuth 1 settings` tab
1. At the bottom of the OAuth 1 settings page, click `Register your application`
1. Register using these settings:
   * `Name`: maprouletteDevLocalhost (any name is fine)
   * `Main Application URL`: <http://127.0.0.1:9000>
   * `Callback URL`: <http://127.0.0.1:9000>
   * `Support URL`: empty string is fine
   * `Request the following permissions from the user`: Select
     * `read their user preferences`
     * `modify their user preferences` (MapRoulette saves the user's MR API key in OSM)
     * `modify the map` (MapRoulette tasks edit the map)

Click `Register` and note the displayed Consumer Key and the Consumer Secret. Copy and paste those keys into the MapRoulette
`conf/dev.conf` file's osm section where it has "CHANGE_ME":

```text
osm {
  consumerKey="CHANGE_ME"
  consumerSecret="CHANGE_ME"
}
```

Stop and start the MapRoulette server to load the updated configuration.

#### Run the MapRoulette front-end

Clone the maproulette3 repository and follow the steps in the [DEVELOPMENT.md "Run the UI from Docker" section](https://github.com/maproulette/maproulette3/blob/develop/DEVELOPMENT.md#run-the-ui-from-docker).
Be sure to execute the `docker run` step that starts the UI.

#### Step 3 - Validation

* Open <http://localhost:3000/> and attempt to log in
  * This should redirect to the development OpenStreetMap server and redirect back to MapRoulette
  * The username should show in the top-right corner
* Create a new Project (this is the easiest way to verify), or a new challenge, or update a project/challenge/task,
  or do some other work that causes various request types to the MapRoulette server

Known setup issues:

* Exceptions with 'java.lang.NumberFormatException: For input string: "CHANGE_ME"'. Edit the `conf/dev.conf` and verify that these are not "CHANGE_ME":
  * `osm.consumerKey`
  * `osm.consumerSecret`
  * `maproulette.super.key`
  * `maproulette.super.accounts`

---

### Step 4: Intelllij Configuration

The server is working end-to-end on command line! Time to import into IntelliJ. Stop the running MapRoulette server from the previous step, if it's still running.

* Open IntelliJ
* Click `Plugins` and verify that the Scala plugin is installed
* Click `Open` and navigate to the MapRoulette source directory and `Open as Project`
  * The project will load
* Create a new `sbt Task` runtime configuration
  * Name it `MapRoulette Server` or similar
  * Set Tasks to  `run`
  * Uncheck 'Use sbt shell`
  * Use these `VM parameters`

    ```text
    -Xms4G
    -Xmx4G
    -Dconfig.file=./conf/dev.conf
    -Dlogger.resource=logback-dev.xml
    ```

#### Step 4 - Validation

Open the front-end UI <http://localhost:3000/> and attempt to log in. It should function.

---

---

### Additional (Optional) Server Configuration

* Open `dev.conf` in a text editor and change at least the following entries:
    * `super.key`: a randomly chosen API key for superuser access
    * `super.accounts`: a comma-separated list of OSM accound IDs whose corresponding MapRoulette users will have superuser access. Can be an empty string.
    * `mapillary.clientId`: a [Mapillary Client ID](https://www.mapillary.com/dashboard/developers), needed if you want to use any of the Mapillary integrations.

## Notes

### Logging Levels

During development, please set `-Dlogger.resource=logback-dev.xml` to have the best experience. The logback dev file sets
many items to the devel level and logs all HTTP request paths and response times.

Any changes to the `conf/logback-dev.xml` will be loaded within about 5 seconds and a service restart is not needed.

To have all request _headers_ sent by the client logged, update the `conf/logback-dev.xml` "org.maproulette.filters" entry to TRACE.

### Deploying on Windows

Windows is not officially supported. However, there is an unofficial [setup guide](https://gist.github.com/3710d7f15534ec747423a3117cd7cc9c).

### SMTP (email) configuration

MapRoulette now supports transmission of emails, for example to inform users
when they receive new in-app notifications. You will need access to an SMTP
server to send emails, and will also need to add SMTP configuration settings to
your configuration file (or whatever configuration mechanism you're using).
`play.mailer.host` is the only required SMTP setting, but most SMTP servers
will also want a username and password.

```
play.mailer.host = "smtp.server.com"
play.mailer.user = "smtpusername"
play.mailer.password = "secret"
```

Many additional SMTP configuration options are available -- see
[play-mailer](https://github.com/playframework/play-mailer/blob/master/README.md)
for a full list.

> Note: If you're doing development, you can set `play.mailer.mock = yes` to
> simply log emails instead of transmitting them

It's also important that you set the `emailFrom` setting to the email address
you wish emails to come from, and that you ensure `publicOrigin` is set so that
links in emails will properly point to your server.

```
emailFrom = "maproulette@yourserver.com"
publicOrigin = "https://www.yourserver.com"
```

By default, notification emails that are to be sent immediately are processed
by a background job every 1 minute. Both the frequency and max number of emails
to process in a single run can be controlled.

```
notifications.immediateEmail.interval = "1 minute"
notifications.immediateEmail.batchSize = 10         # max emails per run
```

Notification emails that are to be sent as a digest are initially processed at
8pm local server time by default, and then every 24 hours thereafter (i.e.
daily digests). Both of these settings can be customized. There is not
currently a maximum limit to the number of emails for digest emails.

```
notifications.digestEmail.startTime = "20:00:00"    # 8pm local server time
notifications.digestEmail.interval = "24 hours"     # once daily
```

## Creating new Challenges

[The wiki for this repo](https://github.com/maproulette/maproulette2/wiki) has some information on creating challenges.

[Challenge API](docs/challenge_api.md) has further information about creating challenges through the API.

See also the Swagger API documentation. You can view the documentation by going to the URL ```/docs/swagger-ui/index.html``` on any MapRoulette instance.

## Dev Docs

- [Creating Challenges](docs/challenge_api.md)
- [Deployment](docs/deployment.md)
- [Github Example](docs/github_example.md)
- [GraphQL](docs/graphql.md)
- [Tag Changes](docs/tag_changes.md)
- [Testing](docs/testing.md)
- [Routes](conf/v2_route/readme.md)

## Contributing

Please fork the project and submit a pull request. See [Postman Docs](postman/README.md) for information on API Testing. The project is integrated with Travis-CI, so PR's will only be accepted once the build compiles successfully. MapRoulette also uses Scalafmt as it's code formatter. This is too keep the code style consistent across all developers. The check will be run first in Travis for the build, so if there are any code style issues it will fail the build immediately. IntelliJ should pick up the formatter and use Scalafmt automatically, however you can also use `sbt scalafmt` to format any and all code for you.

## Contact

Bug and feature requests are best left as an issue right here on Github. For other things, contact maproulette@maproulette.org

MapRoulette now also has a channel #maproulette on the [OSM US Slack community](http://osmus.slack.com). Invite yourself [here](https://osmus-slack.herokuapp.com/)!
