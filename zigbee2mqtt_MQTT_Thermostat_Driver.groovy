metadata {
    definition (name: "z2m Thermostat Driver", namespace: "anemirovsky", author: "Alex Nemirovsky", importUrl: "") {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "ThermostatFanMode"
        capability "RelativeHumidityMeasurement"
        capability "ThermostatSetpoint"
        capability "Sensor"
        capability "Actuator"
        capability "Initialize"

        attribute "mqttConnected", "string"

        command "setHeatingSetpoint", [[name: "Setpoint*", type: "NUMBER"]]
        command "setCoolingSetpoint", [[name: "Setpoint*", type: "NUMBER"]]
    }

    preferences {
        input name: "mqttBroker", type: "string", title: "MQTT Broker Address", required: true
        input name: "deviceTopic", type: "string", title: "Device Topic (e.g., zigbee2mqtt/thermostat)" , required: true
        input name: "username", type: "string", title: "MQTT Username (optional)", required: false
        input name: "password", type: "password", title: "MQTT Password (optional)", required: false
        input name: "logging", type: "bool", title: "Enable Debug Logging", defaultValue: true
    }
} 

def installed() {

    log.info "Installed..."
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
        interfaces.mqtt.unsubscribe(deviceTopic)
        interfaces.mqtt.disconnect()
    }
}

def delayedInitialise() {
    // increase delay by 5 seconds every time
    state.delay = (state.delay ?: 0) + 5
    logDebug "Reconnecting in ${state.delay}s"
    runIn(state.delay, initialize)
}

def fanAuto() {

}

def fanOn() {

}

def fanCirculate() {

}

def fanCirculateOn() {

}

def fanCirculateOff() {

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

def initialize() {
    logDebug "Initialize"
    try {
        // open connection
        interfaces.mqtt.connect("tcp://" + mqttBroker, "hubitat-${device.id}", username, password)
        // subscribe once received connection succeeded status update below        
    } catch(e) {
        log.error "MQTT Initialize error: ${e.message}."
        delayedInitialise()
    }
}


def subscribe() {
    interfaces.mqtt.subscribe(deviceTopic)
    logDebug "Subscribed to topic ${deviceTopic}"
    
}



void parse(String message) {
    if (logging) log.debug "Received message: ${message}"

    def payload = interfaces.mqtt.parseMessage(message)
    def json = parseJson(payload.payload)
    def local_temperature = convertMyTemp(json.local_temperature)
    def occupied_heating_setpoint = convertMyTemp(json.occupied_heating_setpoint)
    def occupied_cooling_setpoint = convertMyTemp(json.occupied_cooling_setpoint)
    def setpoint = convertMyTemp(json.setpoint)
    if (json.local_temperature) {
        sendEvent(name: "temperature", value: local_temperature, unit: getTemperatureScale())
    }

    if (json.humidity) {
        sendEvent(name: "humidity", value: json.humidity, unit: "%")
    }

    if (json.occupied_heating_setpoint) {
        sendEvent(name: "heatingSetpoint", value: occupied_heating_setpoint, unit: getTemperatureScale())
    }

    if (json.occupied_cooling_setpoint) {
        sendEvent(name: "coolingSetpoint", value: occupied_cooling_setpoint, unit: getTemperatureScale())
    }

    if (json.setpoint) {
        sendEvent(name: "thermostatSetpoint", value: setpoint, unit: getTemperatureScale())
    }

    if (json.system_mode) {
        sendEvent(name: "thermostatMode", value: json.system_mode)
    }

  
    
    sendEvent(name: "thermostatFanMode", value: "auto")
    

    if (json.running_state) {
        switch(json.running_state)
        {
            case "idle":
            sendEvent(name: "thermostatOperatingState", value: "idle")
            break

            case "heat":
            sendEvent(name: "thermostatOperatingState", value: "heating")
            break
            
            case "cool":
            sendEvent(name: "thermostatOperatingState", value: "cooling")
            break
        }
        
    }
}

void setHeatingSetpoint(setpoint) {
    def temperatureScale = getTemperatureScale()
    def degrees = new BigDecimal(setpoint).setScale(1, BigDecimal.ROUND_HALF_UP)   
    def celsius = (temperatureScale == "C") ? setpoint as Float : (fahrenheitToCelsius(degrees) as Float).round(2)

    publishMqttMessage(["occupied_heating_setpoint": celsius])
}

void setCoolingSetpoint(setpoint) {
    def temperatureScale = getTemperatureScale()
    def degrees = new BigDecimal(setpoint).setScale(1, BigDecimal.ROUND_HALF_UP)  
 
    def celsius = (temperatureScale == "C") ? setpoint as Float : (fahrenheitToCelsius(degrees) as Float).round(2)

    publishMqttMessage(["occupied_cooling_setpoint": celsius])
}

void setThermostatMode(String mode) {
    if (logging) log.debug "Setting thermostat mode to ${mode}"
    publishMqttMessage(["system_mode": mode])
}

void publishMqttMessage(Map payload) {
    def json = new groovy.json.JsonBuilder(payload).toString()
    interfaces.mqtt.publish("${deviceTopic}/set", json)
}


def convertMyTemp(value) {
    if (value != null) {
        def celsius = new BigDecimal(value).setScale(1, BigDecimal.ROUND_HALF_UP)
        if (convertMyTempScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

def convertMyTempScale() {
    return "${location.temperatureScale}"
}

def logInfo(msg) {
    if (logEnable) log.info msg
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}