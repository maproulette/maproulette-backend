# Create Challenge using Github

One option of creating a challenge that was only mentioned briefly was the ability to create Challenges from Github. The way this works is that the API request will point to a specific repo and retrieve specific files that will be used to create the challenge and the tasks for the challenge. The API request is as follows:

```
POST /api/v2/project/{projectId}/challenge/{username}/{repo}/{name}
HEADER apiKey = <USER_API_KEY>
```

##### Parameters:
- **projectId** - The id of the project that you are creating the challenge under
- **username** - The organization or username of the repo
- **repo** - The name of the repo that contains the file to create the challenge from.
- **name** - The prefix that will be used for the three files that MapRoulette will look for when creating the challenge.

##### Github Files:
- **{name}_geojson.json** - The geojson file with the prefixed {name}. See GeoJSON structure above for specifics on what kind of file is expected.
- **{name}_info.md** - The url of the info file will be included in the Challenge as a file that can be used as extended information on the challenge.
- **{name}_create.json** - The json used to create the Challenge. The JSON would be exactly the same as the JSON that you would provide in the body of the ```POST /api/v2/challenge``` API request.

So for this example we will assume there is a repo called ```https://github.com/maproulette/example``` which contains the following files:
- example_info.md - Contents of this file simply contains extended descriptive markdown.
- example_geojson.json - Contents of file are exactly the same as the example GeoJSON file found [here](example.geojson).
- example_create.json - Contents of the file are as below:
    ```json
    {
        "name":"Github Project",
        "instruction":"Instruction for the example challenge"
    }
    ```
First we need to create a project for our Challenge:
```
POST http://localhost:9000/api/v2/project
HEADER apiKey = user1_apikey
BODY {
    "name":"Example Github Project",
    "description":"Project for Github Example.",
    "enabled":true
}
RESPONSE {
    "id": 1327505,
    "owner": -999,
    "name": "Example Github Project",
    "created": "2018-05-29T16:10:27.688-07:00",
    "modified": "2018-05-29T16:10:27.688-07:00",
    "description": "Project for Github Example.",
    "groups": [],
    "enabled": true,
    "deleted": false,
    "isGlobal": false
}
```
***NOTE:** The owner id will match the ID of the user used based on the APIKey.<br/>
***NOTE:** The id in the response will be different when executing this example.

To create the challenge we execute the following API request:
```
POST http://localhost:9000/api/v2/project/1327505/challenge/maproulette/example/example
HEADER apiKey = user1_apikey
```

After executing this request you should have a new Challenge called "Github Project".
