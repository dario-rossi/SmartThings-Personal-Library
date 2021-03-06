/**
 *  iComfort Connection Manager
 *
 *  Copyright 2015 Dario Rossi
 *
 *  Original Code obtained from Jason Mok located here:
 *  https://github.com/copy-ninja/SmartThings_iComfort
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "iComfort Connection Manager",
    namespace: "dario.rossi",
    author: "Dario Rossi",
    description: "Connection Management to Lennox iComfort Thermostats",
    category: "My Apps",
    version: "2.1",
    /*
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")
    */
    /*
    iconUrl: "https://lh4.ggpht.com/HcqW1DAOAv1ycaZ6LyKE_XwMyVZ1BmmuLWSTzpYb_NHtRBFOzGXWPKpNTfKPwKuDz3o=w300-rw",
    iconX2Url: "https://lh4.ggpht.com/HcqW1DAOAv1ycaZ6LyKE_XwMyVZ1BmmuLWSTzpYb_NHtRBFOzGXWPKpNTfKPwKuDz3o=w300-rw",
    iconX3Url: "https://lh4.ggpht.com/HcqW1DAOAv1ycaZ6LyKE_XwMyVZ1BmmuLWSTzpYb_NHtRBFOzGXWPKpNTfKPwKuDz3o=w300-rw")
	*/
    iconUrl: "http://www.lennox.com/res/images/site/logo-badge.png",
    iconX2Url: "http://www.lennox.com/res/images/site/logo-badge.png",
    iconX3Url: "http://www.lennox.com/res/images/site/logo-badge.png")

preferences {
	page(name: "prefLogIn", title: "iComfort")
	page(name: "prefListDevice", title: "iComfort")
}

/* Preferences */
def prefLogIn() {
	def showUninstall = username != null && password != null
	return dynamicPage(name: "prefLogIn", title: "Connect to iComfort", nextPage:"prefListDevice", uninstall:showUninstall, install: false) {
		section("Application Notes") {
        paragraph required: true, "This application manages the connection to an iComfort Thermostat."
        paragraph required: true, "The version of the application is: " + "2.1"
    	}
        section("Login Credentials"){
			input("username", "text", title: "Username", description: "iComfort Username (case sensitive)")
			input("password", "password", title: "Password", description: "iComfort password (case sensitive)")
		}
		section("Connectivity"){
			input(name: "polling", title: "Server Polling (in Minutes)", type: "int", description: "in minutes", defaultValue: "5" )
		}
        section([mobileOnly:true]) {
            label title: "Assign a name", required: false, description: "Change SmartApp Name to this"
        }
        section("Debug Verbosity"){
        	input(name: "debugVerbosityLevel", title: "Debug Verbosity Level", type: "int", description: "0 (none) to 10 (high)", defaultValue: "0" )
        	}
	}
}

def prefListDevice() {
	if (loginCheck()) {
		def thermostatList = getThermostatList()
		if (thermostatList) {
			return dynamicPage(name: "prefListDevice",  title: "Thermostats", install:true, uninstall:true) {
				section("Select which thermostat/zones to use"){
					input(name: "thermostat", type: "enum", required:false, multiple:true, metadata:[values:thermostatList])
				}
			}
		} else {
			return dynamicPage(name: "prefListDevice",  title: "Error!", install:false, uninstall:true) {
				section(""){
					paragraph "Could not find any devices "
				}
			}
		}
	} else {
		return dynamicPage(name: "prefListDevice",  title: "Error!", install:false, uninstall:true) {
			section(""){
				paragraph "The username or password you entered is incorrect. Try again. "
			}
		}
	}
}


/* Initialization */
def installed() { initialize() }
def updated() { initialize() }

def uninstalled() {
	unschedule()
	def deleteDevices = getAllChildDevices()
	deleteDevices.each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
	unsubscribe()

	// Get initial polling state
	state.polling = [
		last: now(),
		runNow: true
	]

	// Create new devices for each selected doors
	def selectedDevices = []
	def thermostatList = getThermostatList()
	def deleteDevices

	if (settings.thermostat) {
		if (settings.thermostat[0].size() > 1) {
			selectedDevices = settings.thermostat
		} else {
			selectedDevices.add(settings.thermostat)
		}
	}

	selectedDevices.each { dni ->
		def childDevice = getChildDevice(dni)
		if (!childDevice) {
            		addChildDevice("dario.rossi", "iComfort Thermostat", dni, null, ["name": thermostatList[dni],  "completedSetup": true])
		}
	}

	//Remove devices that are not selected in the settings
	if (!selectedDevices) {
		deleteDevices = getAllChildDevices()
	} else {
		deleteDevices = getChildDevices().findAll { !selectedDevices.contains(it.deviceNetworkId) }
	}
	deleteDevices.each { deleteChildDevice(it.deviceNetworkId) }


	//Refresh device
	refresh()

	// Schedule polling
	unschedule()
	schedule("0 0/" + ((settings.polling.toInteger() > 0 )? settings.polling.toInteger() : 1)  + " * * * ?", refresh )
}

/* Access Management */
private loginCheck() {
	apiPut("/DBAcessService.svc/ValidateUser", [query: [UserName: settings.username, lang_nbr: "1"]] ) { response ->
		if (response.status == 200) {
			if (response.data.msg_code == "SUCCESS") {
				return true
			} else {
				return false
			}
		} else {
			return false
		}
	}
}

// Listing all the thermostats you have in iComfort
private getThermostatList() {
	def thermostatList = [:]
    def gatewayList = [:]
    state.data = [:]
	state.lookup = [
		thermostatOperatingState: [:],
		thermostatFanMode: [:],
		thermostatMode: [:],
		program: [:],
		coolingSetPointHigh: [:],
		coolingSetPointLow: [:],
		heatingSetPointHigh: [:],
		heatingSetPointLow: [:],
		differenceSetPoint: [:],
		temperatureRangeF: [:]
	]
	state.list = [
		temperatureRangeC: [],
		program: [:]
	]

	//Get Thermostat Mode lookups
	apiGet("/DBAcessService.svc/GetTstatLookupInfo", [query: [name: "Operation_Mode", langnumber: 0]]) { response ->
		response.data.tStatlookupInfo.each {
			state.lookup.thermostatMode.putAt(it.value.toString(), translateDesc(it.description))
		}
	}

	//Get Fan Modes lookups
	apiGet("/DBAcessService.svc/GetTstatLookupInfo", [query: [name: "Fan_Mode", langnumber: 0]]) { response ->
		response.data.tStatlookupInfo.each {
			state.lookup.thermostatFanMode.putAt(it.value.toString(), translateDesc(it.description))
		}
	}

	//Get System Status lookups
	apiGet("/DBAcessService.svc/GetTstatLookupInfo", [query: [name: "System_Status", langnumber: 0]]) { response ->
		response.data.tStatlookupInfo.each {
			state.lookup.thermostatOperatingState.putAt(it.value.toString(), translateDesc(it.description))
		}
	}

	//Get Temperature lookups
	apiGet("/DBAcessService.svc/GetTemperatureRange", [query: [highpoint: 40, lowpoint: 0]]) { response ->
		response.data.each {
			def temperatureLookup = it.Value.split("\\|")
			state.lookup.temperatureRangeF.putAt(temperatureLookup[1].toString(), temperatureLookup[0].toString())
			state.list.temperatureRangeC.add(temperatureLookup[0].toString())
		}
	}

	//Retrieve all the gateways
	apiGet("/DBAcessService.svc/GetSystemsInfo", [query: [userID: settings.username]]) { response ->
		if (response.status == 200) {
			response.data.Systems.each { device ->
				gatewayList.putAt(device.Gateway_SN,device.System_Name)
			}
		}
	}
	//Retrieve all the Zones
	gatewayList.each { gatewaySN, gatewayName ->
		apiGet("/DBAcessService.svc/GetTStatInfoList", [query: [GatewaySN: gatewaySN, TempUnit: (getTemperatureUnit()=="F")?0:1, Cancel_Away: "-1"]]) { response ->
			if (response.status == 200) {
				//log.debug "zones: " + response.data.tStatInfo
				response.data.tStatInfo.each {
					def dni = [ app.id, gatewaySN, it.Zone_Number ].join('|')
					thermostatList[dni] = ( it.Zones_Installed > 1 )? gatewayName + ": " + it.Zone_Name : gatewayName

					//Get the state of each device
                    if (settings.debugVerbosityLevel.toInteger() > 5) {
                    	log.debug "Thermostat dni: " + dni.toString()
                    }
					state.data[dni] = [
						temperature: it.Indoor_Temp,
						humidity: it.Indoor_Humidity,
						coolingSetpoint: it.Cool_Set_Point,
						heatingSetpoint: it.Heat_Set_Point,
						thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
						thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
						thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
						thermostatProgramMode: it.Program_Schedule_Mode,
						thermostatProgramSelection: it.Program_Schedule_Selection
					]
                    if (settings.debugVerbosityLevel.toInteger() >= 10) {
                    	log.debug "Dario Thermostat Info: " + it.Indoor_Temp.toString()
                    }

					//Get Devices Program lookups
					state.lookup.program.putAt(dni, [:])
					state.list.program.putAt(dni, [])
					apiGet("/DBAcessService.svc/GetTStatScheduleInfo", [query: [GatewaySN: gatewaySN]]) { response2 ->
						if (response2.status == 200) {
							response2.data.tStatScheduleInfo.each {
								state.lookup.program[dni].putAt(it.Schedule_Number.toString(), "Program " + (it.Schedule_Number + 1) + ":\n" + it.Schedule_Name)
								state.list.program[dni].add("Program " + (it.Schedule_Number + 1) + ":\n" + it.Schedule_Name)
							}
						}
					}

					//Get Devices Limit Lookups
					apiGet("/DBAcessService.svc/GetGatewayInfo", [query: [GatewaySN: gatewaySN, TempUnit: "0"]]) { response2 ->
						if (response2.status == 200) {
							state.lookup.coolingSetPointHigh.putAt(dni, response2.data.Cool_Set_Point_High_Limit)
							state.lookup.coolingSetPointLow.putAt(dni, response2.data.Cool_Set_Point_Low_Limit)
							state.lookup.heatingSetPointHigh.putAt(dni, response2.data.Heat_Set_Point_High_Limit)
							state.lookup.heatingSetPointLow.putAt(dni, response2.data.Heat_Set_Point_Low_Limit)
							state.lookup.differenceSetPoint.putAt(dni, response2.data.Heat_Cool_Dead_Band)
						}
					}
				}
			}
		}
	}
	return thermostatList
}



/* api connection */

// HTTP GET call
private apiGet(apiPath, apiParams = [], callback = {}) {
	// set up parameters
	apiParams = [
		uri: "https://" + settings.username + ":" + settings.password + "@services.myicomfort.com",
		path: apiPath,
	] + apiParams

	// try to call
	try {
        if (settings.debugVerbosityLevel.toInteger() > 5) {
			log.debug "HTTP GET request: " + apiParams
		}
		httpGet(apiParams) { response ->
        	if (settings.debugVerbosityLevel.toInteger() > 5) {
				log.debug "HTTP GET response: " + response.data
			}
			callback(response)
		}
	}	catch (Error e)	{
		log.debug "API Error: $e"
	}
}

// HTTP PUT call
private apiPut(apiPath, apiParams = [], callback = {}) {
	// set up final parameters
	apiParams = [
		uri: "https://" + settings.username + ":" + settings.password + "@services.myicomfort.com",
		path: apiPath,
	] + apiParams


	try {
		if (settings.debugVerbosityLevel.toInteger() > 5) {
        	log.debug "HTTP PUT request: " + apiParams
		}
		httpPut(apiParams) { response ->
        	if (settings.debugVerbosityLevel.toInteger() > 5) {
            	log.debug "HTTP PUT response: " + response.data
            }
			callback(response)
		}
	} catch (Error e)	{
		log.debug "API Error: $e"
	}
}

// Updates data for devices
def updateDeviceData() {
	// Next polling time, defined in settings
	def next = (state.polling.last?:0) + ((settings.polling.toInteger() > 0 ? settings.polling.toInteger() : 1) * 60 * 1000)
	if ((now() > next) || (state.polling.runNow)) {
		// set polling states
		state.polling.last = now()
		state.polling.runNow = false

		// update data for child devices
		updateDeviceChildData()
	}
	return true
}

// update child device data
private updateDeviceChildData() {
	def childDevices = getAllChildDevices()
    if (settings.debugVerbosityLevel.toInteger() > 5) {
    	log.debug "updateDeviceChildData - childDevices: " + childDevices
    }
	childDevices.each { device ->
		def childDevicesGateway = getDeviceGatewaySN(device)
		apiGet("/DBAcessService.svc/GetTStatInfoList", [query: [GatewaySN: childDevicesGateway, TempUnit: (getTemperatureUnit()=="F")?0:1, Cancel_Away: "-1"]]) { response ->
			if (response.status == 200) {
                response.data.tStatInfo.each {

                    def thisDeviceNetworkId = [ app.id, childDevicesGateway, it.Zone_Number ].join('|')

                    if (settings.debugVerbosityLevel.toInteger() > 5) {
                		log.debug "updateDeviceChildData - device.deviceNetworkId: " + device.deviceNetworkId
                		log.debug "updateDeviceChildData - Before setting in array - state.data[device.deviceNetworkId]: " + state.data[device.deviceNetworkId]
                        log.debug "updateDeviceChildData - Before setting in array - it: " + it
                        log.debug "updateDeviceChildData - thisDeviceNetworkId: " + thisDeviceNetworkId
                	}

                    if (thisDeviceNetworkId == device.deviceNetworkId) {
                    	state.data[device.deviceNetworkId] = [
							temperature: it.Indoor_Temp,
							humidity: it.Indoor_Humidity,
							coolingSetpoint: it.Cool_Set_Point,
							heatingSetpoint: it.Heat_Set_Point,
							thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
							thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
							thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
							thermostatProgramMode: it.Program_Schedule_Mode,
							thermostatProgramSelection: it.Program_Schedule_Selection
						]
                    }  else {
              	    	if (settings.debugVerbosityLevel.toInteger() > 5) {
                        	log.debug "updateDeviceChildData - thisDeviceNetworkId doesn't match device.deviceNetworkId so skipping it: " + thisDeviceNetworkId + ":" + device.deviceNetworkId
                        }
                    }
					if (settings.debugVerbosityLevel.toInteger() > 5) {
                		log.debug "updateDeviceChildData - device.deviceNetworkId: " + device.deviceNetworkId
                		log.debug "updateDeviceChildData - After setting in array - state.data[device.deviceNetworkId]: " + state.data[device.deviceNetworkId]
                        log.debug "updateDeviceChildData - After setting in array - it: " + it
                	}
                }
			}
		}
	}
}

// lookup value translation
def lookupInfo( lookupName, lookupValue, lookupMode ) {
	if (lookupName == "thermostatFanMode") {
		if (lookupMode) {
			return state.lookup.thermostatFanMode.getAt(lookupValue.toString())
		} else {
			return state.lookup.thermostatFanMode.find{it.value==lookupValue.toString()}?.key
		}
	}
	if (lookupName == "thermostatMode") {
		if (lookupMode) {
			return state.lookup.thermostatMode.getAt(lookupValue.toString())
		} else {
			return state.lookup.thermostatMode.find{it.value==lookupValue.toString()}?.key
		}
	}
	if (lookupName == "thermostatOperatingState") {
		if (lookupMode) {
			return state.lookup.thermostatOperatingState.getAt(lookupValue.toString())
		} else {
			return state.lookup.thermostatOperatingState.find{it.value==lookupValue.toString()}?.key
		}
	}
}

/* for SmartDevice to call */
// Refresh data
def refresh() {
	state.polling = [
		last: now(),
		runNow: true
	]

	//update device to state data
	def updated = updateDeviceData()


    if (settings.debugVerbosityLevel.toInteger() > 5) {
    	log.debug "state data: " + state.data
        log.debug "state lookup: " + state.lookup
		log.debug "state list: " + state.list
    }

	//force devices to poll to get the latest status
	if (updated) {
		// get all the children and send updates
		def childDevice = getAllChildDevices()
		childDevice.each {
			if (settings.debugVerbosityLevel.toInteger() >= 5) {
            	log.debug "Updating " + it.deviceNetworkId
                log.debug "Updating: state.data[it.deviceNetworkId]: " + state.data[it.deviceNetworkId]
            }
			//it.poll()
			it.updateThermostatData(state.data[it.deviceNetworkId])
		}
	}
}

// Get Device Gateway SN
def getDeviceGatewaySN(childDevice) { return childDevice.deviceNetworkId.split("\\|")[1] }

// Get Device Zone
def getDeviceZone(childDevice) { return childDevice.deviceNetworkId.split("\\|")[2] }

// Get single device status
def getDeviceStatus(childDevice) { return state.data[childDevice.deviceNetworkId]

    if (settings.debugVerbosityLevel.toInteger() >= 10) {
    	log.debug "getDeviceStatus.childDevice: " + childDevice
        log.debug "getDeviceStatus.Child Device Network ID: " + childDevice.deviceNetworkId
        log.debug "getDeviceStatus.state.data: " + state.data
        log.debug "getDeviceStatus.state.data[childDevice.deviceNetworkId]: " + state.data[childDevice.deviceNetworkId]
    }
}

// Send thermostat
def setThermostat(childDevice, thermostatData = []) {
	thermostatData.each { key, value ->
		if (key=="coolingSetpoint") { state.data[childDevice.deviceNetworkId].coolingSetpoint = value }
		if (key=="heatingSetpoint") { state.data[childDevice.deviceNetworkId].heatingSetpoint = value }
		if (key=="thermostatFanMode") { state.data[childDevice.deviceNetworkId].thermostatFanMode = value }
		if (key=="thermostatMode") { state.data[childDevice.deviceNetworkId].thermostatMode = value }
	}

	// set up final parameters
	def apiBody = [
		Cool_Set_Point: state.data[childDevice.deviceNetworkId].coolingSetpoint,
		Heat_Set_Point: state.data[childDevice.deviceNetworkId].heatingSetpoint,
		Fan_Mode: lookupInfo("thermostatFanMode",state.data[childDevice.deviceNetworkId].thermostatFanMode.toString(),false),
		Operation_Mode: lookupInfo("thermostatMode",state.data[childDevice.deviceNetworkId].thermostatMode.toString(),false),
		Pref_Temp_Units: (getTemperatureUnit()=="F")?0:1,
		Zone_Number: getDeviceZone(childDevice),
		GatewaySN: getDeviceGatewaySN(childDevice)
	]

    apiPut("/DBAcessService.svc/SetTStatInfo", [contentType: "application/x-www-form-urlencoded", requestContentType: "application/json; charset=utf-8", body: apiBody])

    return state.data[childDevice.deviceNetworkId]
}

// Set program
def setProgram(childDevice, scheduleMode, scheduleSelection) {
	def apiBody = []
    def thermostatData = []
	//Retrieve program info
	state.data[childDevice.deviceNetworkId].thermostatProgramMode = scheduleMode
	if (scheduleMode == "1") {
		state.data[childDevice.deviceNetworkId].thermostatProgramSelection = scheduleSelection
		apiGet("/DBAcessService.svc/GetProgramInfo", [query: [GatewaySN: getDeviceGatewaySN(childDevice), ScheduleNum: scheduleSelection, TempUnit: (getTemperatureUnit()=="F")?0:1]]) { response ->
			if (response.status == 200) {
				state.data[childDevice.deviceNetworkId].coolingSetpoint = response.data.Cool_Set_Point
				state.data[childDevice.deviceNetworkId].heatingSetpoint = response.data.Heat_Set_Point
				state.data[childDevice.deviceNetworkId].thermostatFanMode = lookupInfo("thermostatFanMode",response.data.Fan_Mode.toString(),true)
			}
		}
	}

	// set up final parameters for program
	apiBody = [
		Cool_Set_Point: state.data[childDevice.deviceNetworkId].coolingSetpoint,
		Heat_Set_Point: state.data[childDevice.deviceNetworkId].heatingSetpoint,
		Fan_Mode: lookupInfo("thermostatFanMode",state.data[childDevice.deviceNetworkId].thermostatFanMode.toString(),false),
		Operation_Mode: lookupInfo("thermostatMode",state.data[childDevice.deviceNetworkId].thermostatMode.toString(),false),
		Pref_Temp_Units: (getTemperatureUnit()=="F")?0:1,
		Program_Schedule_Mode: scheduleMode,
		Program_Schedule_Selection: scheduleSelection,
		Zone_Number: getDeviceZone(childDevice),
		GatewaySN: getDeviceGatewaySN(childDevice)
	]

	//Set Thermostat Program
	apiPut("/DBAcessService.svc/SetProgramInfoNew", [contentType: "application/x-www-form-urlencoded", requestContentType: "application/json; charset=utf-8", body: apiBody]) { response ->
		if (response.status == 200) {
			response.data.tStatInfo.each {
				state.data[device.deviceNetworkId] = [
					temperature: it.Indoor_Temp,
					humidity: it.Indoor_Humidity,
					coolingSetpoint: it.Cool_Set_Point,
					heatingSetpoint: it.Heat_Set_Point,
					thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
					thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
					thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
					thermostatProgramMode: it.Program_Schedule_Mode,
					thermostatProgramSelection: it.Program_Schedule_Selection
				]
				thermostatData = [
					coolingSetpoint: it.Cool_Set_Point,
					heatingSetpoint: it.Heat_Set_Point,
					thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
					thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
				]
			}
		}
	}

	//Set Thermostat Values
	return setThermostat(childDevice, thermostatData)
}


def translateDesc(value) {
	switch (value) {
		case "cool only"     : return "cool"
		case "heat only"     : return "heat"
		case "heat or cool"  : return "auto"
		default: return value
	}
}

def getTemperatureUnit() {
	return (location.temperatureScale)?location.temperatureScale:"F"
}

def getThermostatProgramName(childDevice, thermostatProgramSelection) {
	def thermostatProgramSelectionName = state?.lookup?.program[childDevice.deviceNetworkId]?.getAt(thermostatProgramSelection.toString())
	return thermostatProgramSelectionName?thermostatProgramSelectionName:"Unknown"
}

def getThermostatProgramNext(childDevice, value) {
	def sizeProgramIndex = state.list.program[childDevice.deviceNetworkId].size() - 1
	def currentProgramIndex = (state?.list?.program[childDevice.deviceNetworkId]?.findIndexOf { it == value })?state?.list?.program[childDevice.deviceNetworkId]?.findIndexOf { it == value } : 0
	def nextProgramIndex = ((currentProgramIndex + 1) <= sizeProgramIndex)? (currentProgramIndex + 1) : 0
	def nextProgramName = state?.list?.program[childDevice.deviceNetworkId]?.getAt(nextProgramIndex)
	return state?.lookup?.program[childDevice.deviceNetworkId]?.find{it.value==nextProgramName}?.key
}

def getTemperatureNext(value, diffIndex) {
	if (getTemperatureUnit()=="F") {
		return (value + diffIndex)
	} else {
		def currentTemperatureIndex = state?.list?.temperatureRangeC?.findIndexOf { it == value.toString() }.toInteger()
		def nextTemperature = new BigDecimal(state?.list?.temperatureRangeC[currentTemperatureIndex + diffIndex])
		return nextTemperature
	}
}

def getSetPointLimit( childDevice, limitType ) {
	if (getTemperatureUnit() == "F") {
		return  state?.lookup?.getAt(limitType)?.getAt(childDevice.deviceNetworkId)
	} else {
		if (limitType == "differenceSetPoint") {
			return  state?.lookup?.getAt(limitType)?.getAt(childDevice.deviceNetworkId)
		} else {
			def limitTemperatureF = state?.lookup?.getAt(limitType)?.getAt(childDevice.deviceNetworkId)
			def limitTemperatureC = new BigDecimal(state?.lookup?.temperatureRangeF?.getAt(limitTemperatureF.toInteger().toString()))
			return limitTemperatureC
		}
	}
}
