/*
 * Tasmota MQTT Fan Controller using Tuya MCU
 *  Device Driver for Hubitat Elevation
 *  Version 1.0
 *
 * Controls a Tasmota based Dimmer via MQTT broker.
 *
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
 *  1.0 - Initial Version
 *
 */

import groovy.transform.Field
@Field static final List supportedFanSpeeds = ["very-slow", "low", "medium", "high", "off", "on"]


metadata {
    definition (name: "MQTT Tasmota Fan Tuya MCU Cntrl", namespace: "anemirovsky", author: "Alex Nemirovsky") {
        capability "Actuator"
        capability "FanControl"
        capability "SwitchLevel"
        capability "Switch"
        capability "Initialize"
        command "reconnect"
        command "setSpeed", [[name: "Fan speed*", type: "ENUM", description: "Fan speed to set", constraints: supportedFanSpeeds]]

        attribute "fan", "STRING"
    }
}

preferences {
    section("URIs") {
        input "mqttBroker", "string", title: "MQTT Broker Address:Port", required: true
        input "mqttUsername", "string", title: "MQTT Username", required: true
        input "mqttPassword", "string", title: "MQTT Password", required: true
		input "mqttTopicCmnd", "string", title: "MQTT Topic to cmnd", required: true
		input "mqttTopicStat", "string", title: "MQTT Topic to stat", required: true
        input name: "logEnable", type: "bool", title: "Enable light logging", defaultValue: true
        input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() {

    log.info "Installed..."
    device.updateSetting("enableDebug",[type:"bool", value: true])
    device.updateSetting("enableDesc",[type:"bool", value: true])
}

// tele topic subscription processing
def parse(String description) {
    //logDebug description
	
    mqtt = interfaces.mqtt.parseMessage(description)
	//logDebug mqtt
	
	json = new groovy.json.JsonSlurper().parseText(mqtt.payload)
	logDebug json

    if (state.check > 0) {
        state.check++
    }
    

    switch(json.speed)
    {

        case "very-slow":
            sendEvent(name: "speed", value: "very-slow")
            sendEvent(name: "level", value: 5, unit: "%", isStateChange: true)
            break
        case "low":
            sendEvent(name: "speed", value: "low")
            sendEvent(name: "level", value: 20, unit: "%", isStateChange: true)
            break
        case "medium":        
            sendEvent(name: "speed", value: "medium")
            sendEvent(name: "level", value: 60, unit: "%", isStateChange: true)
            break
        case "high":
            sendEvent(name: "speed", value: "high")
            sendEvent(name: "level", value: 100, unit: "%", isStateChange: true)
            break
    }
        switch(json.power)
    {
        case "off":
            sendEvent(name: "switch", value: "off")
            break
        case "on":
            sendEvent(name: "switch", value: "on")
            break
    }
       
    
}

String setSpeed(speed) {
    if (enableDebug) log.debug "setSpeed (${speed})"

    switch (speed) {
        case "off":
            fanOff()
            break
        case "on":
            fanOn()
            break
        case "very-slow":
            fanVerySlowOn()
            break
        case "low":
            fanLowOn()
            break
        case "medium-low":
        case "medium":
        case "medium-high":
        
            fanMediumOn()
            break
        case "high":
            fanHighOn()
            break
    }
}

def fanVerySlowOn()
{
    logInfo "fanVerySlowOn"
    publishCmnd("tuyasend4", "3,0" )
}

def fanLowOn()
{
    logInfo "fanLowOn"
    publishCmnd("tuyasend4", "3,1" )
}

def fanMediumOn()
{
    logInfo "fanMediumOn"
    publishCmnd("tuyasend4", "3,2" )
}

def fanHighOn()
{
    logInfo "fanHighOn"
    publishCmnd("tuyasend4", "3,3" )
}


def on()
{
    logInfo "On"
    fanOn()
}
def off()
{
    logInfo "Off"
    fanOff()
}


def fanOn() {
    logInfo "fanOn"
    publishCmnd("POWER", "on" )
}


def fanOff() {
    logInfo "fanOff"
    publishCmnd("POWER", "off" )
}


String cycleSpeed() {
    if (enableDebug) log.debug "cycleSpeed"

    String currentSpeed = device.currentValue("fan") ?: "off"

    switch (currentSpeed) {
       case "off":
          return fanLowOn()
       break
       case "low":
          return fanMediumOn()
       break
       case "medium":
          return fanHighOn()
       break
       case "high":
          return fanOff()
       break
    }
}

def setLevel(level, duration)
{
    setLevel(level)
}

def setLevel(level) {
    if (level < 1) off()
    if (level > 1 && level < 6) fanVerySlowOn()f
    if (level > 5 && level < 21) fanLowOn()
    if (level > 20 && level < 61) fanMediumOn()
    if (level > 60) fanHighOn()
}




def publishCmnd(command, arg) {
    logDebug "Publish ${settings.mqttTopicCmnd} ${command}"
    def topic = settings.mqttTopicCmnd + "/" + command
    interfaces.mqtt.publish(topic, arg)
}

def updated() {
    log.info "Updated..."
    initialize()
    
}

def uninstalled() {
    disconnect()
}

def reconnect() {
    disconnect()
    initialize()
}

def disconnect() {
    if (state.connected) {
        log.info "Disconnecting from MQTT"
        interfaces.mqtt.unsubscribe(settings.mqttTopicStat)
        interfaces.mqtt.disconnect()
    }
}

def delayedInitialise() {
    // increase delay by 5 seconds every time
    state.delay = (state.delay ?: 0) + 5
    logDebug "Reconnecting in ${state.delay}s"
    runIn(state.delay, initialize)
}

def initialize() {
    logDebug "Initialize"
    try {
        // open connection
        interfaces.mqtt.connect("tcp://" + settings.mqttBroker, "hubitat_tasmota_dimmer_${device.id}", settings.mqttUsername, settings.mqttPassword)
        // subscribe once received connection succeeded status update below        
    } catch(e) {
        log.error "MQTT Initialize error: ${e.message}."
        delayedInitialise()
    }
}

def subscribe() {
    interfaces.mqtt.subscribe(settings.mqttTopicStat + "/FAN")
    logDebug "Subscribed to topic ${settings.mqttTopicStat}/FAN"
    
//    runIn(3, beginCheck)
}

def beginCheck() {
    // check connection
    // 1. turn light on
    // 2. turn light off
    // 3. if not received any MQTT messages reconnect
    state.check = 1
    on()
    runIn(3, checkConnection)  
}

def checkConnection() {
    if (state.check == 1) {
        // check started but not completed. reconnect
        log.warn 'Light not reacting'
        state.remove('check')
        delayedInitialise()
    } else if (state.check > 1) {
        state.remove('check')
        off()    
        log.info 'Light check completed'
    }
}

def mqttClientStatus(String status){
    // This method is called with any status messages from the MQTT client connection (disconnections, errors during connect, etc) 
    // The string that is passed to this method with start with "Error" if an error occurred or "Status" if this is just a status message.
    def parts = status.split(': ')
    switch (parts[0]) {
        case 'Error':
            log.warn status
            switch (parts[1]) {
                case 'Connection lost':
                    state.connected = false
                    //sendEvent(name: "presence", value: "not present", descriptionText: "MQTT disconnected")
                    state.delay = 0
                    delayedInitialise()
                    break
                case 'send error':
                    state.connected = false
                    //sendEvent(name: "presence", value: "not present", descriptionText: "MQTT disconnected")
                    state.delay = 0
                    delayedInitialise()
                    break
            }
            break
        case 'Status':
            log.info "MQTT ${status}"
            switch (parts[1]) {
                case 'Connection succeeded':
                    state.connected = true
                    // without this delay the `parse` method is never called
                    // (it seems that there needs to be some delay after cinnection to subscribe)
                    runInMillis(100, subscribe)
                    break
            }
            break
        default:
            logDebug "MQTT ${status}"
            break
    }
}

def logInfo(msg) {
    if (logEnable) log.info msg
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}
