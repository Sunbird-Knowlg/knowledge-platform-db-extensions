# knowledge-platform-db-extensions local setup
This application is used to generate neo4j transactions as log events. This readme file contains the instruction to set up and run the knowledge-platform-db-extensions application in local machine.
### Prerequisites:
* Neo4j-3.3.0
* Java 8
* Any Linux Operating System

### Build knowledge-platform-db-extensions application:
1. After forking and cloning the repository, go to the repository path(/knowledge-platform-db-extensions) and run the below mvn build command:
```shell
cp neo4j-extensions/learning-graph-extension/src/main/resources/test-log4j2.xml neo4j-extensions/learning-graph-extension/src/main/resources/log4j2.xml
mvn clean install -DskipTests
```
2. In each module target folder we can see jar file got created. we will be using these jar files as neo4j plugins, which will explained in next following steps.
### Neo4j database setup in docker:
1. First, we need to get the neo4j image from docker hub using the following command.
```shell
docker pull neo4j:3.3.0 
```
2. We need to create the neo4j instance, By using the below command we can create the same and run in a container.
```shell
docker run --name local_neo4j -p7474:7474 -p7687:7687 -d \
-v ~/neo4j/data:/var/lib/neo4j/data \
-v ~/neo4j/logs:/var/lib/neo4j/logs \
-v ~/neo4j/plugins:/var/lib/neo4j/plugins \
--env NEO4J_dbms_connector_https_advertised__address="localhost:7473" \
--env NEO4J_dbms_connector_http_advertised__address="localhost:7474" \
--env NEO4J_dbms_connector_bolt_advertised__address="localhost:7687" \
--env NEO4J_AUTH=none \
neo4j:3.3.0
```
> - `--name` -  Name your container (avoids generic id)
> - `-p` - Specify container ports to expose.
Using the -p option with ports 7474 and 7687 allows us to expose and listen for traffic on both the HTTP and Bolt ports. Having the HTTP port means we can connect to our database with Neo4j Browser, and the Bolt port means efficient and type-safe communication requests between other layers and the database.
> - `-d` - This detaches the container to run in the background, meaning we can access the container separately and see into all of its processes.
> - `-v` - The next several lines start with the -v option. These lines define volumes we want to bind in our local directory structure. so we can access certain files locally.
> - `--env` - Set config as environment variables for Neo4j database. Using Docker on Windows will also need a couple of additional configurations because the default 0.0.0.0 address that is resolved with the above command does not translate to localhost in Windows. We need to add environment variables to our command above to set the advertised addresses.

**Note:** *By default, Neo4j requires authentication and requires us to first login with neo4j/neo4j and set a new password. We will skip this password reset by initializing the authentication none when we create the Docker container using the --env NEO4J_AUTH=none.*

3. After running the above command, neo4j instance will be created and container starts running, we can verify the same by accessing [neo4j browser](http://localhost:7474/browser).

### Running neo4j with knowledge-platform-db-extensions plugins:
1. Get custom-procedures & learning-graph-extensions jar files from the corresponding modules target directory and place them in neo4j plugins folder which we created in our local directory.
```shell
cp neo4j-extensions/custom-procedures/target/custom-procedures-1.1.jar ~/neo4j/plugins
cp neo4j-extensions/learning-graph-extension/target/learning-graph-extension-1.1.jar ~/neo4j/plugins
```
2. Restart the neo4j container in docker to load plugins.
3. To verify whether transaction events are generating or not, execute the following query from neo4j cypher-shell:
```cql
CREATE (n:domain{IL_UNIQUE_ID:"neo4j-test-txn-event-gen-s1",name:"Neo4J Test Transaction Event Generation - Scenario 1"})
```
4. In knowledge-platform-db-extensions/neo4j-extensions/learning-graph-extension/src/main/resources/log4j2.xml file we have specified the path to generate the log file. The logs will be generated in the same path. To access the docker container files, open container shell by executing the below command:
```shell
docker exec -it [container_name] sh
cd /var/lib/neo4j/logs/plugins/txn-handler
cat learning_graph_event_neo4j.log
```
or you can access these log files in your file-system:
```shell
cd ~/neo4j/logs/plugins/txn-handler
cat learning_graph_event_neo4j.log
```

The log event will be generated in the following format:
```log
2021-05-03 11:02:56,711 {"ets":1620039776661,"nodeUniqueId":"neo4j-test-txn-event-gen-s1","requestId":null,"channel":null,"transactionData":{"properties":{"name":{"ov":null,"nv":"Neo4J Test Transaction Event Generation - Scenario 1"},"IL_UNIQUE_ID":{"ov":null,"nv":"neo4j-test-txn-event-gen-s1"}}},"mid":"3a3d7d9a-6fa1-4370-8f79-3d46b13a3240","operationType":"CREATE","nodeGraphId":1,"label":"Neo4J Test Transaction Event Generation - Scenario 1","graphId":"domain","userId":"ANONYMOUS","createdOn":"2021-05-03T11:02:56.660+0000"}
```
