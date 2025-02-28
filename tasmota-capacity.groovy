/*
 * Tasmota MQTT Switch
 *  Device Driver for Hubitat Elevation
 *  Version 1.0
 *
 * Controls a Tasmota based Switch via MQTT broker.
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



metadata {
    definition (name: "MQTT Tasmota Capacity", namespace: "anemirovsky", author: "Alex Nemirovsky") {
        capability "Actuator"
        capability "Battery"
        capability "Initialize"
        attribute 'battery', 'number'
        attribute 'lastUpdated', 'date'
        attribute 'batteryStatus', 'string'
        command "reconnect"
        command 'setBattery', [[name:'batteryReading', type: 'NUMBER', description: 'Enter the new battery reading (%)' ] ]
    }
}

preferences {
    section("URIs") {
        input "mqttBroker", "string", title: "MQTT Broker Address:Port", required: true
        input "mqttUsername", "string", title: "MQTT Username", required: true
        input "mqttPassword", "string", title: "MQTT Password", required: true
		input "mqttTopic", "string", title: "MQTT Topic to % Capacity", required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {

    log.info "Installed..."
}

def parse(String description) {
    //logDebug description
	
    payload = Integer.parseInt(interfaces.mqtt.parseMessage(description).payload)
    payload = Math.round(payload)
	
    if (state.check > 0) {
        state.check++
    }
   // log.info "payload: ${payload}"
    //Update the battery and batteryStatus attributes
    sendEvent(name: 'battery',       value: "${payload}" ) 
    
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
        interfaces.mqtt.unsubscribe(settings.mqttTopic)
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
    interfaces.mqtt.subscribe(settings.mqttTopic)
    logDebug "Subscribed to topic ${settings.mqttTopic}"
    
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

def logInfo( msg ) {
    if (logEnable) log.info msg
}

def logDebug( msg ) {
    if (logEnable) log.debug msg
}
