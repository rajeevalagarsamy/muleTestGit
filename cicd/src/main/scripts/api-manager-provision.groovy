import groovy.json.JsonSlurper
import groovy.json.JsonOutput 
import groovyx.net.http.*
import groovyx.net.http.ContentType.*
import groovyx.net.http.Method.*

class CICDUtil
{
    static def int WARN=1;
    static def int INFO=2;
    static def int DEBUG=3;
    static def int TRACE=4;



    static def logLevel = DEBUG;  //root logger level

    static def log (java.lang.Integer level, java.lang.Object content)
    {
        if (level <= logLevel)
        {
            def logPrefix = new Date().format("YYYYMMdd-HH:mm:ss") 
            if (level == WARN)
            {
                logPrefix += " WARN"
            }
            if (level == INFO)
            {
                logPrefix += " INFO"
            }
            if (level == DEBUG)
            {
                logPrefix += " DEBUG"
            }
            if (level == TRACE)
            {
                logPrefix += " TRACE"
            }
            println logPrefix + " : " + content 
        }

    }
   
    def getAnypointToken(props)
    {
        log(DEBUG,  "START getAnypointToken")


        def username=props.username
        def password=props.password 


        log(TRACE, "username=" + username)
        log(TRACE, "password=" + password)

        def urlString = "https://anypoint.mulesoft.com/accounts/login"

        def message = 'username='+username+'&password='+password

        def headers=["Content-Type":"application/x-www-form-urlencoded", "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "POST", message, headers)

        if ( connection.responseCode =~ '2..') 
        {

        }else
        {
            throw new Exception("Failed to get the login token!")
        }

        def response = "${connection.content}"

        def token = new JsonSlurper().parseText(response).access_token

        log(INFO, "Bearer Token: ${token}")

        log(DEBUG,  "END getAnypointToken")

        return token

    }
    
    def init ()
    {
        
      
        
        def props = ['username':System.properties.'anypoint.user', 
                     'password': System.properties.'anypoint.password',
                     'orgId': System.properties.'orgId',
                     'version': System.properties.'version',
                     'envId': System.properties.'envId',
                     'assetId': System.properties.'assetId',
                     'assetVersion': System.properties.'assetVersion',
                     'filePath' : System.properties.'filePath',
                     'policyFile' : System.properties.'policyFile',
                     'path': System.getProperty("user.dir")

                    ]

        log(DEBUG,  "props->" + props)
        return props;
    }


    def provisionAPIManager(props)
    {
        def token = getAnypointToken(props);

        def result = getAPIInstanceByExchangeAssetDetail(props, token);

        log(INFO, "apiInstance=" + result)

        return result
    }

    def getAPIInstanceByExchangeAssetDetail(props, token)
    {

        log(DEBUG,  "START getAPIInstanceByExchangeAssetDetail")

        def apiInstance
        def apiDiscoveryName
        def apiDiscoveryVersion
        def apiDiscoveryId
        def policyDetails

        def urlString = "https://anypoint.mulesoft.com/exchange/api/v1/assets/"+props.orgId+"/"+props.assetId

        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "GET", null, headers)

        if (connection.responseCode == 404)
        {
            log(INFO, "API Instance for " + props.assetId + " is not found in API Manager")

        } 
        else if (connection.responseCode == 200)
        {
            log(INFO, "API Instances for " + props.assetId + " has been found in the platform ");

            def response = "${connection.content}"

            def allAPIInstances = new JsonSlurper().parseText(response).instances;

            allAPIInstances.each{ 
                log(INFO, it)
                if (it.environmentId == props.envId && it.version == props.version)
                {
                    apiInstance = it;
                    apiDiscoveryName = "groupId:"+props.orgId+":assetId:"+ prosp.assetId
                    apiDiscoveryVersion = apiInstance.name
                    apiDiscoveryId = apiInstance.id
                }
            }

            log(INFO, "apiInstance for env " + props.envId + " is " + apiInstance);

        }

        if (apiInstance == null)
        {
            apiInstance = createAPIInstance(token, props)
            
            apiDiscoveryName = apiInstance.autodiscoveryInstanceName
            apiDiscoveryVersion = apiInstance.productVersion
            apiDiscoveryId = apiInstance.id
            policyDetails = applyPolicy (token, apiDiscoveryId , props)
                    

        }

        def result = ["apiInstance": apiInstance, "apiDiscoveryName": apiDiscoveryName, "apiDiscoveryVersion":apiDiscoveryVersion, "apiDiscoveryId": apiDiscoveryId]

        log(DEBUG,  "END getAPIInstanceByExchangeAssetDetail")

        return result

    }
    
    def applyPolicy (token, apiId , props)
    {
        log(DEBUG,  "START applyPolicy");
        def inputFile = new File(props.policyFile)
        
       // def requestTemplate = '{"policyTemplateId": "294","groupId": "68ef9520-24e9-4cf2-b2f5-620025690913","assetId": "client-id-enforcement","assetVersion": "1.1.2","configurationData":{"credentialsOriginHasHttpBasicAuthenticationHeader":"customExpression","clientIdExpression": "#[attributes.headers[]]","clientSecretExpression": "#[attributes.headers[]]" },"pointcutData":null}'
        
            
        def request = new JsonSlurper().parseText(inputFile.text);     
        
        def message = JsonOutput.toJson(request)
        
        log(INFO, "applyPolicy request message=" + message);

        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+props.orgId+"/environments/"+props.envId + "/apis/"+apiId+"/policies"
                       
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "POST", message, headers)

        def response = "${connection.content}" 
        
        if ( connection.responseCode =~ '2..') 
        {
            log(INFO, "the Policy is created successfully! statusCode=" + connection.responseCode)
        }
        else
        {
            throw new Exception("Failed to create Policy ! statusCode=${connection.responseCode} responseMessage=${response}")
        }
        def policy = new JsonSlurper().parseText(response)

        log(DEBUG, "Policy Details "+ policy )
        
        log(DEBUG,  "END applyPolicy")

        return policy;
    
    }


    def createAPIInstance(token, props)
    {
        log(DEBUG,  "START createAPIInstance")

        //def requestTemplate = '{ "spec": { "groupId": null,"assetId": null,"version": null }, "endpoint": {"uri": null,"proxyUri": null,"isCloudHub": false,"proxyRegistrationUri": null, "muleVersion4OrAbove": true,"type": "http", "deploymentType": "HY","policiesVersion": null,  "referencesUserDomain": false,"responseTimeout": null,"wsdlConfig": null },"instanceLabel": null}'
        
        def apiFileName = props.filePath+props.envId+'_api_spec.json'
        
        def inputApiFile = new File(apiFileName)
        
        def request = new JsonSlurper().parseText(inputApiFile.text);

        def message = JsonOutput.toJson(request)
        
        log(INFO, "createAPIInstance request message=" + message);

        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+props.orgId+"/environments/"+props.envId + "/apis"
        
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "POST", message, headers)

        def response = "${connection.content}"

        if ( connection.responseCode =~ '2..') 
        {
            log(INFO, "the API instance is created successfully! statusCode=" + connection.responseCode)
        }
        else
        {
            throw new Exception("Failed to create API Instance! statusCode=${connection.responseCode} responseMessage=${response}")
        }
    

        def apiInstance = new JsonSlurper().parseText(response)

        log(DEBUG,  "END createAPIInstance")

        return apiInstance;
    }

    static def doRESTHTTPCall(urlString, method, payload, headers)
    {
        log(DEBUG,  "START doRESTHTTPCall")

        log(INFO, "requestURl is " + urlString)

        def url = new URL(urlString)

        def connection = url.openConnection()
        
        headers.keySet().each {
            log(INFO, it + "->" + headers.get(it))
            connection.setRequestProperty(it, headers.get(it))
        }
       

        connection.doOutput = true

        if (method == "POST")
        {
            connection.setRequestMethod("POST")
            def writer = new OutputStreamWriter(connection.outputStream)
            writer.write(payload)
            writer.flush()
            writer.close()
        }
        else if (method == "GET")
        {
            connection.setRequestMethod("GET")
        }

        
        connection.connect();
        

        log(DEBUG,  "END doRESTHTTPCall")

        return connection

    }
    


    def persisteAPIDiscoveryDetail (props, result)
    {
        //def outputFileName = props.filePath+props.envId+'_output.txt'
        
        //def outputFile = new File(outputFileName)

        //assert outputFile.canWrite() : "${props.targetPropFile} file cannot be write"

        //outputFile.append("apiDiscoveryVersion="+result.apiDiscoveryVersion+"\n")
        //outputFile.append("apiDiscoveryName="+result.apiDiscoveryName+"\n")
        //outputFile.append("apiDiscoveryId="+result.apiDiscoveryId+"\n")
        
        Properties props1 = new Properties()
        def config = props.path+"/src/main/resources/config.properties"
        log(DEBUG,  "Config.properties path" + config )
        File propsFile = new File(config)
        props1.load(propsFile.newDataInputStream())
        log(DEBUG,  "Existing api.id=" + props1.getProperty('api.id') )
        props1.setProperty('api.id',result.apiDiscoveryId.toString())
        log(DEBUG,  "Afer change api.id=" + props1.getProperty('api.id') )
        props1.store(propsFile.newWriter(), null)
  

    }

    static void main(String[] args) {


        CICDUtil util = new CICDUtil();

        def props = util.init();
      
          //def exchangeDetail = util.extractExchangeAssetDetail(props);

          def result = util.provisionAPIManager(props);

          util.persisteAPIDiscoveryDetail(props, result)
          
         

       } 
}