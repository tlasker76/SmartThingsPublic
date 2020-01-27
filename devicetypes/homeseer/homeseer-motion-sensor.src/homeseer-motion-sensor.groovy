/**
 *  HS-MS100+
 *
 *  Copyright 2018 HomeSeer
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
 * Version 1.0 1/15/18
 */
metadata {
	definition (
		name: "HomeSeer Motion Sensor", 
		namespace: "HomeSeer", 
		author: "support@homeseer.com"
	) {
		capability "Sensor"
		capability "Motion Sensor"		
		capability "Battery"
		capability "Configuration"
		capability "Refresh"
		capability "Health Check"
		
		attribute "lastCheckin", "string"		
		
		fingerprint mfr:"000C", prod:"0201", model:"0009"
	}
	
	simulator { }
	
	preferences {		
		input "motionClearedDelay", "enum",
			title: "Motion Cleared Delay:",
			defaultValue: motionClearedDelaySetting,
			required: false,
			displayDuringSetup: true,
			options: motionClearedDelayOptions.collect { it.name }
		input "motionSensitivity", "enum",
			title: "Motion Detection Sensitivity:",
			defaultValue: motionSensitivitySetting,
			required: false,
			displayDuringSetup: true,
			options: motionSensitivityOptions.collect { it.name }		
		input "wakeUpInterval", "enum",
			title: "Checkin Interval:",
			defaultValue: checkinIntervalSetting,
			required: false,
			displayDuringSetup: true,
			options: checkinIntervalOptions.collect { it.name }
		input "batteryReportingInterval", "enum",
			title: "Battery Reporting Interval:",
			defaultValue: batteryReportingIntervalSetting,
			required: false,
			displayDuringSetup: true,
			options: checkinIntervalOptions.collect { it.name }		
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"motion", type: "generic", width: 6, height: 4, canChangeIcon: false){
			tileAttribute ("device.motion", key: "PRIMARY_CONTROL") {
				attributeState "inactive", 
					label:'No Motion', 
					icon:"st.motion.motion.inactive", 
					backgroundColor:"#cccccc"
				attributeState "active", 
					label:'Motion', 
					icon:"st.motion.motion.active", 
					backgroundColor:"#00a0dc"
			}		
		}			
		
		
		standardTile("refresh", "device.refresh", width: 2, height: 2) {
			state "refresh", label:'Refresh', action: "refresh", icon:"st.secondary.refresh-icon"
		}
		
		valueTile("battery", "device.battery", decoration: "flat", width: 2, height: 2){
			state "battery", label:'${currentValue}% battery', unit:""
		}
					
		main "motion"
		details(["motion", "refresh", "battery"])
	}
}

// Sets flag so that configuration is updated the next time it wakes up.
def updated() {	
	// This method always gets called twice when preferences are saved.
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {		
		state.lastUpdated = new Date().time
		logTrace "updated()"

		logForceWakeupMessage "The configuration will be updated the next time the device wakes up."
		state.pendingChanges = true
	}		
}

// Initializes the device state when paired and updates the device's configuration.
def configure() {
	logTrace "configure()"
	def cmds = []
	def refreshAll = (!state.isConfigured || state.pendingRefresh || !settings?.ledEnabled)
	
	if (!state.isConfigured) {
		logTrace "Waiting 1 second because this is the first time being configured"		
		sendEvent(getEventMap("motion", "inactive", false))		
		cmds << "delay 1000"
	}
	
	configData.sort { it.paramNum }.each { 
		cmds += updateConfigVal(it.paramNum, it.size, it.value, refreshAll)	
	}
	
	if (!cmds) {
		state.pendingChanges = false
	}
	
	if (refreshAll || canReportBattery()) {
		cmds << batteryGetCmd()
	}
	
	initializeCheckin()
	cmds << wakeUpIntervalSetCmd(checkinIntervalSettingMinutes)
		
	if (cmds) {
		logDebug "Sending configuration to device."
		return delayBetween(cmds, 1000)
	}
	else {
		return cmds
	}	
}

private updateConfigVal(paramNum, paramSize, val, refreshAll) {
	def result = []
	def configVal = state["configVal${paramNum}"]
	
	if (refreshAll || (configVal != val)) {
		result << configSetCmd(paramNum, paramSize, val)
		result << configGetCmd(paramNum)
	}	
	return result
}

private initializeCheckin() {
	// Set the Health Check interval so that it can be skipped once plus 2 minutes.
	def checkInterval = ((checkinIntervalSettingMinutes * 2 * 60) + (2 * 60))
	
	sendEvent(name: "checkInterval", value: checkInterval, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

// Required for HealthCheck Capability, but doesn't actually do anything because this device sleeps.
def ping() {
	logDebug "ping()"	
}

// Forces the configuration to be resent to the device the next time it wakes up.
def refresh() {	
	logForceWakeupMessage "The sensor data will be refreshed the next time the device wakes up."
	state.pendingRefresh = true
}

private logForceWakeupMessage(msg) {
	logDebug "${msg}  You can force the device to wake up immediately by removing and re-inserting the battery."
}

// Processes messages received from device.
def parse(String description) {
	def result = []
    
    logDebug "parse description: $description"
	
	sendEvent(name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)	
	
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result += zwaveEvent(cmd)
	}
	else {
		logDebug "Unable to parse description: $description"
	}
	return result
}

private getCommandClassVersions() {
	[
		0x30: 2,	// Sensor Binary
		0x31: 5,	// Sensor Multilevel
		0x59: 1,  // AssociationGrpInfo
		0x5A: 1,  // DeviceResetLocally
		0x5E: 2,  // ZwaveplusInfo
		0x70: 1,  // Configuration
		0x71: 3,  // Notification v4
		0x72: 2,  // ManufacturerSpecific
		0x73: 1,  // Powerlevel
		0x80: 1,  // Battery
		0x84: 2,  // WakeUp
		0x85: 2,  // Association
		0x86: 1		// Version (2)
	]
}

// Updates devices configuration, requests battery report, and/or creates last checkin event.
def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
	logTrace "WakeUpNotification: $cmd"
	def result = []
	
	if (state.pendingChanges != false) {
		result += configure()
	}
	else if (state.pendingRefresh || canReportBattery()) {
		result << batteryGetCmd()
	}
	else {
		logTrace "Skipping battery check because it was already checked within the last ${batteryReportingIntervalSetting}."
	}
	
	if (result) {
		result << "delay 2000"
	}
	result << wakeUpNoMoreInfoCmd()
	
	return sendResponse(result)
}

private sendResponse(cmds) {
	def actions = []
	cmds?.each { cmd ->
		actions << new physicalgraph.device.HubAction(cmd)
	}	
	sendHubCommand(actions)
	return []
}

// Creates the event for the battery level.
def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	logTrace "BatteryReport: $cmd"
	def val = (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)
	if (val > 100) {
		val = 100
	}
	state.lastBatteryReport = new Date().time	
	logDebug "Battery ${val}%"
	[
		createEvent(getEventMap("battery", val, null, null, "%"))
	]
}	

// Stores the configuration values so that it only updates them when they've changed or a refresh was requested.
def zwaveEvent(physicalgraph.zwave.commands.configurationv1.ConfigurationReport cmd) {	
	def name = configData.find { it.paramNum == cmd.parameterNumber }?.name
	if (name) {	
		def val = hexToInt(cmd.configurationValue, cmd.size)
	
		logDebug "${name} = ${val}"
	
		state."configVal${cmd.parameterNumber}" = val
	}
	else {
		logDebug "Parameter ${cmd.parameterNumber}: ${cmd.configurationValue}"
	}
	state.isConfigured = true
	state.pendingRefresh = false	
	return []
}

// Creates motion events.
def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
	def result = []	
	// logTrace "NotificationReport: $cmd"
	
	if (cmd.notificationType == 0x07) {
		switch (cmd.event) {
			case 0x00:
				logDebug "Motion Inactive"				
				result << createEvent(getEventMap("motion", "inactive"))
				break
			case 0x08:
				logDebug "Motion Active"
				result << createEvent(getEventMap("motion", "active"))
				break
			default:
				logDebug "Unknown Notification Event: ${cmd}"
		}
	}
	return result
}

// Ignoring event because motion events are being handled by notification report.
def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd) {
	// logTrace "SensorBinaryReport: $cmd"
	return []
}

// Logs unexpected events from the device.
def zwaveEvent(physicalgraph.zwave.Command cmd) {
	logDebug "Unhandled Command: $cmd"
	return []
}

private getEventMap(name, value, displayed=null, desc=null, unit=null) {	
	def isStateChange = (device.currentValue(name) != value)
	displayed = (displayed == null ? isStateChange : displayed)
	def eventMap = [
		name: name,
		value: value,
		displayed: displayed,
		isStateChange: isStateChange
	]
	if (desc) {
		eventMap.descriptionText = desc
	}
	if (unit) {
		eventMap.unit = unit
	}	
	logTrace "Creating Event: ${eventMap}"
	return eventMap
}

private wakeUpIntervalSetCmd(minutesVal) {
	state.checkinIntervalMinutes = minutesVal
	logTrace "wakeUpIntervalSetCmd(${minutesVal})"
	
	return zwave.wakeUpV2.wakeUpIntervalSet(seconds:(minutesVal * 60), nodeid:zwaveHubNodeId).format()
}

private wakeUpNoMoreInfoCmd() {
	return zwave.wakeUpV2.wakeUpNoMoreInformation().format()
}

private batteryGetCmd() {
	return zwave.batteryV1.batteryGet().format()
}

private configGetCmd(paramNum) {
	return zwave.configurationV1.configurationGet(parameterNumber: paramNum).format()
}

private configSetCmd(paramNum, size, val) {
	return zwave.configurationV1.configurationSet(parameterNumber: paramNum, size: size, scaledConfigurationValue: val).format()
}

private canReportBattery() {
	def reportEveryMS = (batteryReportingIntervalSettingMinutes * 60 * 1000)
		
	return (!state.lastBatteryReport || ((new Date().time) - state.lastBatteryReport > reportEveryMS)) 
}


// Settings
private getLedEnabledSetting() {
	return settings?.ledEnabled ?: findDefaultOptionName(ledEnabledOptions)
}

private getMotionSensitivitySetting() {
	return settings?.motionSensitivity	?: findDefaultOptionName(motionSensitivityOptions)
}

private getMotionClearedDelaySetting() {
	return settings?.motionClearedDelay ?: findDefaultOptionName(motionClearedDelayOptions)
}

private getCheckinIntervalSettingMinutes() {
	return convertOptionSettingToInt(checkinIntervalOptions, checkinIntervalSetting) ?: 720
}

private getCheckinIntervalSetting() {
	return settings?.wakeUpInterval ?: findDefaultOptionName(checkinIntervalOptions)
}

private getBatteryReportingIntervalSettingMinutes() {
	return convertOptionSettingToInt(checkinIntervalOptions, batteryReportingIntervalSetting) ?: checkinIntervalSettingMinutes
}

private getBatteryReportingIntervalSetting() {
	return settings?.batteryReportingInterval ?: findDefaultOptionName(checkinIntervalOptions)
}


// Configuration Parameters
private getConfigData() {
	return [
		[paramNum: 12, name: "Motion Sensitivity", value: convertOptionSettingToInt(motionSensitivityOptions, motionSensitivitySetting), size: 1],
		[paramNum: 18, name: "Motion Cleared Delay", value: convertOptionSettingToInt(motionClearedDelayOptions, motionClearedDelaySetting), size: 2],		
	]	
}

private getMotionSensitivityOptions() {
	return getSensitivityOptions(5, 1, 8, 1)	
}

private getSensitivityOptions(defaultVal, minVal, maxVal, interval) {	
	def options = []

	options << [name: "8 (Most Sensitive)", value: minVal]

	(2..24).each {
		minVal += interval
		options << [name: "${it}", value: minVal]
	}

	options << [name: "1 (Least Sensitive)", value: maxVal]
	
	options.each {
		if (it.value == defaultVal) {
			it.name = formatDefaultOptionName("${it.name}")
		}
	}
	return options
}

private getMotionClearedDelayOptions() {
	[
		[name: "10 Seconds", value: 10],		
		[name: "15 Seconds", value: 15],
		[name: "30 Seconds", value: 30],
		[name: "45 Seconds", value: 45],
		[name: "1 Minute", value: 60],
		[name: "2 Minutes", value: 120],
		[name: "3 Minutes", value: 180],
		[name: "4 Minutes", value: 240],
		[name: "5 Minutes", value: 300],
		[name: "7 Minutes", value: 420],
		[name: formatDefaultOptionName("10 minutes"), value: 600],
        [name: "20 Minutes", value: 1200]
	]
}

private getCheckinIntervalOptions() {
	[
		[name: "10 Minutes", value: 10],
		[name: "15 Minutes", value: 15],
		[name: "30 Minutes", value: 30],
		[name: "1 Hour", value: 60],
		[name: "2 Hours", value: 120],
		[name: "3 Hours", value: 180],
		[name: "6 Hours", value: 360],
		[name: "9 Hours", value: 540],
		[name: formatDefaultOptionName("12 Hours"), value: 720],
		[name: "18 Hours", value: 1080],
		[name: "24 Hours", value: 1440]
	]
}

private convertOptionSettingToInt(options, settingVal) {
	return safeToInt(options?.find { "${settingVal}" == it.name }?.value, 0)
}

private formatDefaultOptionName(val) {
	return "${val}${defaultOptionSuffix}"
}

private findDefaultOptionName(options) {
	def option = options?.find { it.name?.contains("${defaultOptionSuffix}") }
	return option?.name ?: ""
}

private getDefaultOptionSuffix() {
	return "   (Default)"
}

private safeToInt(val, defaultVal=-1) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

private safeToDec(val, defaultVal=-1) {
	return "${val}"?.isBigDecimal() ? "${val}".toBigDecimal() : defaultVal
}

private hexToInt(hex, size) {
	if (size == 2) {
		return hex[1] + (hex[0] * 0x100)
	}
	else {
		return hex[0]
	}
}

private canCheckin() {
	// Only allow the event to be created once per minute.
	def lastCheckin = device.currentValue("lastCheckin")
	return (!lastCheckin || lastCheckin < (new Date().time - 60000))
}

private convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
		return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
	}
	else {
		return "$dt"
	}	
}

private isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time) 
}

private logDebug(msg) {
	if (settings?.debugOutput || settings?.debugOutput == null) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	// log.trace "$msg"
}