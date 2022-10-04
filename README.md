# MapRoulette API
[![Build Status](https://travis-ci.org/maproulette/maproulette2.svg?branch=dev)](https://travis-ci.org/maproulette/maproulette2)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=maproulette_maproulette2&metric=alert_status)](https://sonarcloud.io/dashboard?id=maproulette_maproulette2)
![GitHub release (latest by date)](https://img.shields.io/github/v/release/maproulette/maproulette2)

Welcome to the repository for the MapRoulette back-end server code. The MapRoulette back-end exposes the MapRoulette API, which the MapRoulette front-end web application depends on. The source code for the web application is in [a separate repository](https://github.com/osmlab/maproulette3).

**If you just want to deploy the MapRoulette back-end, [we have a 🚢 Docker image 🚢 for that](https://github.com/maproulette/maproulette2-docker)**. This is especially useful if you want to contribute to the MapRoulette front-end and don't intend to touch the back-end.

The MapRoulette back-end is built on these core technologies:

* Postgres 10.1 with PostGIS 2.2.1
* Play Framework 2.8.0 with Scala 2.13.2

## Requirements

* A Java 11 SDK 
* PostgreSQL 10.1
* PostGIS 2.2.1
* [Scala Build Tool](https://www.scala-sbt.org/download.html) 1.3.8 

Newer versions may work but are untested.

## Setup

### Register an OAuth app with OSM

Before beginning, you'll need to register an app with OpenStreetMap to get a consumer key and secret key. For development and testing, you may wish to do this on the [OSM dev server](http://master.apis.dev.openstreetmap.org) (you will need to setup a new user account if you haven't used the dev server before).

To register your app, login to your account, go to "My Settings", click on "oauth settings", and then click "Register your Application" near the bottom. Give your app a name and application URL (you can simply use http://localhost:9000 if desired) and leave the other URL fields blank. In the permissions section, check "read their user preferences" and "modify the map" and then click the "Register" button at the bottom to get your consumer and secret keys. Be sure to take note of them.

Remember that you need to create the OAuth application on the OSM server that you will be testing against.

For more details on the app registration process, see the [OSM OAuth wiki page](http://wiki.openstreetmap.org/wiki/OAuth).

### PostgreSQL Database Setup

* Create a PostgreSQL superuser `osm`: `createuser -sW osm`. Use `osm` as the password.
* Create a new PostgreSQL database `mp_dev` owned by `osm`: `createdb -O osm mp_dev`.

> On Linux you will need to execute these commands as the `postgres` user, `sudo -u postgres createuser -sP osm && sudo -u postgres createdb -O osm mp_dev`

### Server Configuration

* Clone MapRoulette 2 `git clone https://github.com/maproulette/maproulette2.git`
* `cd` into the newly created directory `cd maproulette2`
* Create a configuration file by copying the template file `cp conf/dev.conf.example conf/dev.conf`
* Open `dev.conf` in a text editor and change at least the following entries:
    * `super.key`: a randomly chosen API key for superuser access
    * `super.accounts`: a comma-separated list of OSM accound IDs whose corresponding MapRoulette users will have superuser access. Can be an empty string.
    * `mapillary.clientId`: a [Mapillary Client ID](https://www.mapillary.com/dashboard/developers), needed if you want to use any of the Mapillary integrations.
    * `osm.consumerKey` and `osm.consumerSecret`: the OAuth keys from your OSM OAuth app you created earlier.
* Save `dev.conf`

Now you're ready to run the MapRoulette backend.

## Running

You run the MapRoulette backend in development mode like this:

`sbt run -Dconfig.resource=./conf/dev.conf -Dlogger.resource=logback-dev.xml`

> This will take some time the first run as dependencies are downloaded.

Confirm that it's all working by getting `http://localhost:9000/api/v2/challenges` which should return `[]` (since we don't have any challenges yet).

> This will take some time on first run as artifacts are compiled.

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

### SSL

Openstreetmap.org recently moved to SSL only. This means that to authenticate against any SSL server you are now required to make sure that Java trusts the OSM SSL certificates. This is not very difficult to do, however they need to be completed for it to work. The steps below are for linux/Mac systems.

1. execute the following command ```openssl s_client -showcerts -connect "www.openstreetmap.org:443" -servername www.openstreetmap.org </dev/null | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > osm.pem```
2. execute the following command ```keytool -importcert -noprompt -trustcacerts -alias www.openstreetmap.org -file osm.pem -keystore osmcacerts -storepass openstreetmap```
3. Run server using sbt ```sbt run -Dconfig.resource=dev.conf -Djavax.net.ssl.trustStore=/path/to/file/osmcacerts -Djavax.net.ssl.trustStorePassword=openstreetmap```

If you want to connect to the dev servers you can simply replace all instances of www.openstreetmap.org with master.apis.dev.openstreetmap.org

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
