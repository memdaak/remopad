import Foundation
import Network

@MainActor
final class UDPTransport: ObservableObject {
    @Published private(set) var statusText = "未连接"
    @Published private(set) var isConnected = false
    @Published private(set) var isConnecting = false

    private let queue = DispatchQueue(label: "com.remopad.udp", qos: .userInteractive)
    private var connection: NWConnection?
    private var sequence: UInt32 = 0
    private var pairingCode: UInt32 = 0

    func connect(host: String, port: UInt16, pairingCode: UInt32) {
        disconnect()

        guard !host.isEmpty, let networkPort = NWEndpoint.Port(rawValue: port) else {
            showConfigurationError()
            return
        }

        self.pairingCode = pairingCode
        isConnecting = true
        statusText = "正在连接…"

        let parameters = NWParameters.udp
        parameters.allowLocalEndpointReuse = true
        let newConnection = NWConnection(
            host: NWEndpoint.Host(host),
            port: networkPort,
            using: parameters
        )

        newConnection.stateUpdateHandler = { [weak self, weak newConnection] state in
            Task { @MainActor in
                guard let self, self.connection === newConnection else { return }
                switch state {
                case .ready:
                    self.isConnecting = false
                    self.isConnected = true
                    self.statusText = "已连接到 \(host):\(port)"
                    self.sendPing()
                case .waiting(let error):
                    self.isConnecting = true
                    self.isConnected = false
                    self.statusText = "等待网络：\(error.localizedDescription)"
                case .failed(let error):
                    self.isConnecting = false
                    self.isConnected = false
                    self.statusText = "连接失败：\(error.localizedDescription)"
                case .cancelled:
                    self.isConnecting = false
                    self.isConnected = false
                    self.statusText = "未连接"
                default:
                    break
                }
            }
        }

        connection = newConnection
        newConnection.start(queue: queue)
    }

    func disconnect() {
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        isConnected = false
        isConnecting = false
        statusText = "未连接"
    }

    func showConfigurationError() {
        statusText = "请检查 IP、端口和六位配对码"
    }

    func sendMove(dx: Float, dy: Float) {
        var payload = Data()
        payload.appendFloat32LE(dx)
        payload.appendFloat32LE(dy)
        send(type: 1, payload: payload)
    }

    func sendScroll(dx: Float, dy: Float) {
        var payload = Data()
        payload.appendFloat32LE(dx)
        payload.appendFloat32LE(dy)
        send(type: 2, payload: payload)
    }

    func sendButton(_ button: UInt8, isDown: Bool) {
        var payload = Data([button, isDown ? 1 : 0])
        payload.appendIntegerLE(UInt16(0))
        send(type: 3, payload: payload)
    }

    func sendClick(_ button: UInt8) {
        sendButton(button, isDown: true)
        sendButton(button, isDown: false)
    }

    private func sendPing() {
        send(type: 4, payload: Data())
    }

    private func send(type: UInt8, payload: Data) {
        guard isConnected, let connection else { return }

        sequence &+= 1
        var packet = Data("RMP1".utf8)
        packet.append(1)
        packet.append(type)
        packet.appendIntegerLE(UInt16(payload.count))
        packet.appendIntegerLE(sequence)
        packet.appendIntegerLE(pairingCode)
        packet.append(payload)

        connection.send(content: packet, completion: .contentProcessed { [weak self] error in
            guard let error else { return }
            Task { @MainActor in
                self?.statusText = "发送失败：\(error.localizedDescription)"
            }
        })
    }
}

private extension Data {
    mutating func appendIntegerLE<T: FixedWidthInteger>(_ value: T) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { bytes in
            append(contentsOf: bytes)
        }
    }

    mutating func appendFloat32LE(_ value: Float) {
        appendIntegerLE(value.bitPattern)
    }
}
