# Deployment

This document is to help understanding how MapRoulette is deployed to production servers. There are various ways to do this but this focuses on how MapRoulette is deployed to maproulette.org. This uses maproulette2-docker to deploy both the backend and frontend. Below you can find the github repo's that are used for the deployment.

Backend - https://github.com/maproulette/maproulette-backend.git<br/>
Frontend - https://github.com/maproulette/maproulette3.git<br/>
docker - https://github.com/maproulette/maproulette2-docker.git

### Step by Step

1. Login to box that maproulette will be deployed too.
2. Either clone or copy the github project maproulette2-docker onto the current box.
3. cd maproulette2-docker
4. Edit docker.conf file and update properties that are specific to your instance. See Configuration section for more specific information on this.
5. (Optional) Modify github locations if you wish to deploy a custom version of MapRoulette. The files and lines that you will have to edit are as follows:
    1. Dockerfile LINE 18 & 35
    2. run_docker.sh LINE 12 & 13
6. Execute `./docker.sh`

After the docker.sh script has completed the instance will be stood up on localhost port 80. The standard MapRoulette actually deploys the instance on port 8080 as it has Nginx front it to handle SSL correctly and forward various paths correctly. 

To start the service on a different port do the following:
1. Edit file run_docker.sh
2. modify LINE 42 and replace "80:80" with "8080:8080" or whatever port you want to deploy the service on.

### Configuration

### Nginx

### FAQ

My server is not responding correctly?

Error when logging into MapRoulette using OSM?