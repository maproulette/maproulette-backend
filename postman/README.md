# Testing MapRoulette 2 API with Postman

MapRoulette 2 can be easily tested using Postman with the [Collection File](maproulette2.postman_collection.json). To test the API execute the following:

1. Download Postman if you don't already have it [here](https://www.getpostman.com/)
2. Open Postman and import the [MapRoulette 2 Collection file](maproulette2.postman_collection.json)
3. Open the Postman runner.
4. Run tests for collection folders Project, Challenge and Tag.

This will run through a variety of API calls and verify that the calls come back as expected.

### Important Note

In MapRoulette Play Framework we have enabled CORS for our Swagger API documentation. So using swagger-ui you are able to view and test the API calls. However by enabling the CORS filter it causes authorized requests to fail with a 403 forbidden. This is due to Postman adding the "origin" header to the request with the value "file://". Unfortunately this is a completely invalid URI and so fails with a exception during the CORS filter process in Play. So to run the API test suites in Postman you will need to disable the CORS filter on your server prior to running the tests. To do this you simple need to comment out the following line in conf/application.conf:
 
```
Line 118: //play.http.filters="org.maproulette.filters.Filters"
```
