#include "bridge_ios.h"
#include "common/bridge_core.h"
#include <vector>
#include <string>
#include <cstdio>
#include <cstring>
#include <atomic>
#include <mutex>
#include <thread>

static void carStateToBridge(const CarState& cs, BridgeCarState* out) {
    out->egoSteeringAngle = cs.egoSteeringAngle;
    out->egoSpeed = cs.egoSpeed;
    out->leftBlinker = cs.leftBlinker;
    out->rightBlinker = cs.rightBlinker;
    out->gear = cs.gear;
    out->adasOn = cs.adasOn;
    out->leftBlindSpot = cs.leftBlindSpot;
    out->rightBlindSpot = cs.rightBlindSpot;
    out->fusedSpeedLimit = cs.fusedSpeedLimit;
    out->stopLineDist = cs.stopLineDist;
    out->trafficLightColor = cs.trafficLightColor;
    out->anyDoorOpen = cs.anyDoorOpen;
    out->buckleStatus = cs.buckleStatus;
    out->accSetSpeed = cs.accSetSpeed;
    out->fullPackEnergy = cs.fullPackEnergy;
    out->nominalEnergyRemaining = cs.nominalEnergyRemaining;
    out->energyBuffer = cs.energyBuffer;
    out->maxRegenPower = cs.maxRegenPower;
    out->maxDischargePower = cs.maxDischargePower;
    out->packVoltage = cs.packVoltage;
    out->packCurrent = cs.packCurrent;
    out->packTMin = cs.packTMin;
    out->packTMax = cs.packTMax;
    out->odometer = cs.odometer;
    out->acTemp = cs.acTemp;
    out->acTempRight = cs.acTempRight;
    out->hvacFanLevel = cs.hvacFanLevel;
    out->hvacPowerState = cs.hvacPowerState;
    out->hvacAcMode = cs.hvacAcMode;
    out->hvacRecirc = cs.hvacRecirc;
    out->hvacKeepClimateOn = cs.hvacKeepClimateOn;
    out->powerLevel = cs.powerLevel;
    out->madsActive = cs.madsActive;
    out->selfdriveActive = cs.selfdriveActive;
    out->experimentalMode = cs.experimentalMode;
    out->changingLane = cs.changingLane;
}

static std::atomic<bool> receiveLoopRunning{false};
static std::mutex receiveLoopMutex;
static std::thread receiveLoopThread;

extern "C" {

void* bridge_create_context(void) {
    return bridge::createContext();
}

void bridge_delete_context(void* ctx) {
    bridge::deleteContext(static_cast<Context*>(ctx));
}

char* bridge_discover_publisher(void* ctx, const char* endpoint, const char* initialIp, int timeoutMs) {
    std::string result = bridge::discoverPublisher(
        static_cast<Context*>(ctx), endpoint, initialIp, timeoutMs);
    if (result.empty()) return nullptr;
    return strdup(result.c_str());
}

void* bridge_create_sub_sockets(void* ctx, const char** endpoints, int count, const char* address) {
    std::vector<std::string> eps;
    for (int i = 0; i < count; i++) eps.emplace_back(endpoints[i]);
    auto* group = bridge::createSubSockets(static_cast<Context*>(ctx), eps, address);
    for (const auto& ep : eps) {
        printf("[bridge_ios] subscribed to '%s' at %s\n", ep.c_str(), address);
    }
    return group;
}

void bridge_delete_sub_sockets(void* groupPtr) {
    bridge::deleteSubSockets(static_cast<SubSocketGroup*>(groupPtr));
}

void* bridge_create_vehicle_decoder(const char** dbcContents, const int* busIndices, int count, const char* vehicleType) {
    std::vector<std::string> dbc;
    std::vector<int> buses;
    for (int i = 0; i < count; i++) {
        dbc.emplace_back(dbcContents[i]);
        buses.push_back(busIndices[i]);
    }
    return bridge::createVehicleDecoder(dbc, buses, vehicleType);
}

void bridge_destroy_vehicle_decoder(void* decoder) {
    bridge::destroyVehicleDecoder(static_cast<VehicleDecoder*>(decoder));
}

void bridge_decode_can_frame(void* decoderPtr, int bus, uint32_t address, const uint8_t* data, int len, BridgeCarState* out) {
    auto* decoder = static_cast<VehicleDecoder*>(decoderPtr);
    decoder->updateFrame(bus, address, data, static_cast<size_t>(len));
    decoder->updateMapper();
    carStateToBridge(decoder->state(), out);
}

// Starts the receive loop on a worker thread and returns immediately. The
// thread is owned here, so bridge_stop_receive_loop() always has a handle to
// join: a stop issued before or during start can no longer return early and
// leave the loop running on freed sockets.
void bridge_start_receive_loop(void* groupPtr, void* decoderPtr, void* callbackContext, bridge_car_state_callback_t callback) {
    auto* group = static_cast<SubSocketGroup*>(groupPtr);
    auto* decoder = static_cast<VehicleDecoder*>(decoderPtr);

    std::lock_guard<std::mutex> lock(receiveLoopMutex);
    if (receiveLoopThread.joinable()) return;  // one loop at a time
    receiveLoopRunning.store(true);
    receiveLoopThread = std::thread([group, decoder, callbackContext, callback] {
        BridgeCarState bridgeState = {};
        bridge::runReceiveLoop(group, decoder, receiveLoopRunning,
            [&bridgeState, callbackContext, callback](const CarState& state) {
                carStateToBridge(state, &bridgeState);
                callback(callbackContext, &bridgeState);
            });
    });
}

// Signals the worker to exit and joins it, so no callback can run after this
// returns and the caller may free sockets, decoder and callback context.
void bridge_stop_receive_loop(void) {
    std::thread worker;
    {
        std::lock_guard<std::mutex> lock(receiveLoopMutex);
        receiveLoopRunning.store(false);
        worker = std::move(receiveLoopThread);
    }
    if (!worker.joinable()) return;
    if (worker.get_id() == std::this_thread::get_id()) {
        worker.detach();  // called from the loop's own callback; cannot join self
    } else {
        worker.join();
    }
}

}
