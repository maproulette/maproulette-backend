# Creating Challenges using the API

This document will go over everything that is needed to generate Challenges with tasks in various ways through the API. Although the API is quite powerful it can be difficult to navigate at times due to the various options that are available to the user of the API. The MapRoulette UI will usually hide all these options from the user and provide a clean interface to build challenges from. This document will describe all the various options and how to use them through the API. 

For more information and more advanced options on building projects using the API see the swagger documentation. The swagger documentation can be found on any instance of MapRoulette with the following url: ```/docs/swagger-ui/index.html```. To see the swagger documentation for maproulette.org follow this [link](https://maproulette.org/docs/swagger-ui/index.html).

## Creating a Project

The first thing that is required for any Challenge is a Project. The hierarchy for objects in MapRoulette look as follows:

```Project -> Challenge -> Task```

Creating a Project is fairly simple using the API. A basic example is as follows:

```
POST /api/v2/project
HEADER apiKey = <USER_API_KEY>
BODY {
    "name":"Example Project",
    "description":"Example project for documentation.",
    "enabled":true
}  
```

## Creating a Challenge

Challenges and their children Tasks can be created in a multitude of ways using the Challenge API. In this document we will be going over the following methods:

- [Manually building a Challenge](#manually-building-a-challenge)
- [Manually building a Task](#manually-building-a-task)
- [Batched Task creation](#batched-task-creation)
- [Task creation within a Challenge](#task-creation-within-a-challenge)
- [Task creation from Overpass Query](#task-creation-from-overpass-query)
- [Task creation from GeoJSON](#task-creation-from-geojson)

#### Manually Building a Challenge

Building a Challenge through the API is fairly trivial and at the very basic level can be completed with the following API request:

```
POST /api/v2/challenge
HEADER apiKey = <USER_API_KEY>
BODY {
    "name":"Example Challenge",
    "parent":<PROJECT_ID>,
    "instruction":"Instruction for the example challenge"
}
```

As shown in the example there are 3 basic options that are required to create a challenge. However there are many more options that are available that can be used when building a challenge:

- **description** - A basic description for the challenge
- **infoLink** - A link to a page that has information about the challenge
- **difficulty** - The difficulty of the challenge, 1 - EASY; 2 - NORMAL; 3 - EXPERT. Default is NORMAL.
- **blurb** - A small blurb about the challenge
- **enabled** - Whether the challenge will be available in searches and on the main MapRoulette page. By default is set to false.
- **featured** - Whether the challenge will be featured in MapRoulette or not. By default is set to false.
- **checkinComment** - The default comment that would be used when fixing a task.
- **overpassQL** - By default empty, if set it assumes that the tasks to be created will be based off the provided overpass query in this field.
- **remoteGeoJSON** - By default empty, if set it assumes that the tasks to be created will be based off the geoJSON found at the URL provided in this field. If overpassQL has been provided it will be used instead of this value.
- **defaultPriority** - The default priority of any tasks that are not matched with any of the priority rules.
- **highPriorityRule** - Any tasks that match the priority rule will be set to high, ie. viewed first.
- **mediumPriorityRule** - Any tasks that match the priority rule will be set to medium, ie. viewed after all high priority tasks have been viewed.
- **lowPriorityRule** - Any tasks that match the priority rule will be set to low, ie. viewed after all the medium priority tasks have been viewed.
- **defaultZoom** - The default zoom level for the challenge, which defaults to 13.
- **minZoom** - The minimum zoom level allowed for the challenge, which defaults to 1.
- **maxZoom** - The maximum zoom level allowed for the challenge, which defaults to 19.
- **defaultBasemap** - 
- **customBasemap** - URL for a custom basemap to be applied to the map for a specific challenge.
- **updateTasks** - Whether to update the tasks on a periodic basis. This will also delete stale tasks.

#### Manually Building a Task

Building a task through the API can be done using the following API request:

```
POST /api/v2/task
HEADER apiKey = <USER_API_KEY>
BODY {
    "name":"Example Task",
    "parent": <PARENT_CHALLENGE_ID>,
    "instruction":"Example Task instruction",
    "geometries":{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[102.1,0.6]},"properties":{"id": "test2"}},{"type":"Feature","geometry":{"type":"Point","coordinates":[102.2,0.7]},"properties":{ "identifier":{"value":1111} }}]}
}
```

This is to create a single task for a challenge, the id of the challenge is provided in the body of the Task. Generally speaking, using this is not super efficient. Better options (which will be described later) would be to create the tasks immediately with the Challenge or to create the tasks in batches. 

#### Batched Task Creation

All objects in MapRoulette can be created in batches using this style of API, in the POST functions for Project, Challenge or Task. Batched Task creation is the most useful, as generally speaking the number of tasks being created will be much higher compared to the number of projects or challenges being created. Here is the API request:

```
POST /api/v2/tasks
HEADER apiKey = <USER_API_KEY>
BODY [
    {
        "name":"Task1",
        "parent":1,
        "instruction":"Task1 instruction",
        "geometries":{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[102.1,0.6]},"properties":{"id": "test2"}},{"type":"Feature","geometry":{"type":"Point","coordinates":[102.2,0.7]},"properties":{ "identifier":{"value":1111} }}]}
    },
    {
        "name":"Task2",
        "parent":1,
        "instruction":"Task2 instruction",
        "geometries":{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[102.1,0.6]},"properties":{"id": "test2"}},{"type":"Feature","geometry":{"type":"Point","coordinates":[102.2,0.7]},"properties":{ "identifier":{"value":1111} }}]}
    },
    ...
]
```

For challenges you would use the API ```/api/v2/challenges``` and for projects you would use the API ```/api/v2/projects```.

#### Task Creation within a Challenge

Another option is to create the tasks directly within the challenge. This can be done using the "children" key in the Challenge object as follows:

```
POST /api/v2/challenge
HEADER apiKey = <USER_API_KEY>
BODY {
    "name":"Example Challenge",
    "parent":<PROJECT_ID>,
    "instruction":"Instruction for the example challenge",
    "children":[
        ...
        <BATCHED TASK OBJECTS>
        ...
    ]
}
```

This approach can be done at the project level as well. So in a single API request you can potentially create the entire hierarchy down to the task using the children key like so

```
    {
        <PROJECT PARAMS>,
        "children": [
            {
                <CHALLENGE PARAMS>,
                "children": [
                    {
                        <TASK PARAMS>
                    },
                    ...
                ]
            },
            ...
        ]
    }   
```

#### Task creation from Overpass Query

A challenge can be created with an overpass query. If an overpass query is supplied the status of the challenge will immediately go into "BUILDING" and then will execute the overpass query request to the overpass servers and with the results of the query build tasks from it. Once all tasks have been successfully completed the status of the Challenge will move to "COMPLETED". If any tasks failed to be created from the query, the status of the Challenge will go to "PARTIALLY_LOADED" indicating that not all tasks could be created. If there is a complete failure, for instance if the overpass servers are unavailable then it will go to status "FAILED" and push the reason for failure into the "statusMessage" field of the challenge. When you request the challenge it will always return with "status" and "statusMessage" fields which can be used to evaluate the current state of the Challenge. 

An example of creating a Challenge using the overpass query option is show in the [**Examples**](#examples) section.

#### Task creation from GeoJSON

Creating tasks for a challenge using GeoJSON can be done in several different ways. 

The first option is simply to include a URL to the geoJSON in the "remoteGeoJSON" field during Challenge creation. This will then create the Challenge and run through a similar workflow that is used during task and challenge creation for overpass queries. 

The second option is to use the github API Challenge creation method. This allows you to store everything needed in Github that will then be used to creation the Challenge. More information about this including an example can be found in the [**Examples**](#examples) section.

The third option is to provide the GeoJSON directly through the API. This can be done in three different ways:
1. Using the "localGeoJSON" key during the creation of the challenge
    ```
        POST /api/v2/challenge
        HEADER apiKey = <USER_API_KEY>
        BODY {
            "name":"Example Project",
            "parent":<PROJECT_ID>,
            "instruction":"Instruction for the example challenge",
            "localGeoJSON":<VALID GEOJSON>
        }
    ```
2. Using the API ```PUT /api/v2/challenge/{id}/addTasks``` where the body of the request is the GeoJSON that you wish to use to create the tasks.

3. Using the API ```PUT /api/v2/challenge/{id}/addFileTasks?lineByLine=false```. This is a multipart HTTP request where the body is essentially a binary file. That file is the GeoJSON that you want to use to create the files. If you set the "lineByLine" query string parameter to true you can upload file that contains GeoJSON line delimited. The usefulness of this is that you can control the task creation cleaner, as each line will equate to a single task. 

##### GeoJSON Structure

The GeoJSON provided in any of the above usages must conform to the GeoJSON standards found in [RFC 7946](https://tools.ietf.org/html/rfc7946), you can also find out further information at [geojson.org](http://geojson.org). There is one caveat to this, in that you can also provide LineByLine GeoJSON. That is GeoJSON that as a whole is not valid, as each line in a file contains valid GeoJSON, but each line is a separate GeoJSON entity. The LineByLine GeoJSON is only an option when using files, and cannot be used when supplying the GeoJSON in the body of a Challenge. You can however use the LineByLine GeoJSON when supplying it as the body of the API ```PUT /api/v2/challenge/{id}/addTasks```.

## Examples

The following sections contains full working examples of challenge and tasks creation. All examples are fairly straight forward and do not contain any advanced options like priorities.
All the examples where created using the GeoJSON provided [here](example.geojson) or the LineByLine GeoJSON provided [here](linebyLine.geojson).

All examples also assume it is executing against a local MapRoulette instance. Changing the example to run against another server would simply mean modifying the server name and port to match the desired server name and port. We also assume that the header apiKey field will be "user1_apikey".

#### Create Challenge using GeoJSON

In this example we are going to create a Challenge with tasks based on the example geojson file provided [here](example.geojson). We will give examples of all three ways that the challenge can be created.

###### Create by "LocalGeoJSON"
```
POST http://localhost:9000/api/v2/challenge
HEADER apiKey = user1_apiKey
BODY {
    "name":"Local GeoJSON Challenge",
    "parent":<PROJECT_ID>,
    "instruction":"Instruction for local GeoJSON challenge",
    "localGeoJSON":<PASTE example.geojson CONTENTS HERE>
}
```

###### Create by addTasks API
```
POST http://localhost:9000/api/v2/challenge/<CHALLENGE_ID>/addTasks
HEADER apiKey = user1_apiKey
BODY <CONTENTS of example.geojson>
```

#### Create Challenge using Overpass

To create a challenge with tasks you execute the following API:
```
POST http://localhost:9000/api/v2/challenge
HEADER apiKey = user1_apikey
BODY {
     "name":"Overpass Challenge",
     "parent":<PROJECT_ID>,
     "instruction":"Instruction for the overpass challenge",
     "overpassQL":"[out:xml[timeout:250];(way[\"highway\"~\"motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|residential|unclassified\"][\"name\"~\"National Forest\"](47.148633511301426,-121.90567016601562,47.95774347215711,-120.45684814453124););out meta;>;out meta qt;"
 }
```

This will create a challenge containing tasks match the following criteria:
1. Within the bounding box 47.148633511301426,-121.90567016601562,47.95774347215711,-120.45684814453124
2. Has a highway with a tag value of motorway, trunk, primary, secondary, tertiary, motorway_link, trunk_link, primary_link, secondary_link, tertiary_link, residential or unclassified.
3. The name of the feature is contains "National Forest"

#### [Create Challenge using Github](github_example.md)
