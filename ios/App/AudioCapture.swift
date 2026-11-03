import AVFAudio
import Foundation
import WoVoiceCore

/// AVAudioEngine invokes its tap outside the main actor. The lock protects all mutable DSP state.
nonisolated final class AudioAccumulator: @unchecked Sendable {
    private let lock = NSLock()
    private var samples: [Int16] = []
    private var collecting = false
    private var rms = 0.0
    private let converter: AVAudioConverter
    private let format: AVAudioFormat
    init(input: AVAudioFormat) throws {
        guard let output = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: input, to: output) else {
            throw APIError(code: "AUDIO_FORMAT", message: "The microphone format is unavailable.")
        }
        format = output; self.converter = converter
    }
    func begin() { lock.lock(); defer { lock.unlock() }; samples.removeAll(keepingCapacity: true); collecting = true; converter.reset() }
    func finish() -> [Int16] { lock.lock(); defer { lock.unlock() }; collecting = false; let result = samples; samples = []; rms = 0; return result }
    func state() -> (level: Double, duration: Double) { lock.lock(); defer { lock.unlock() }; return (rms, Double(samples.count)/16000) }
    func ingest(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); defer { lock.unlock() }
        guard collecting, samples.count < 960000 else { return }
        let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 16000 / buffer.format.sampleRate) + 64)
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
        var supplied = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true; status.pointee = .haveData; return buffer
        }
        guard error == nil, let channel = output.int16ChannelData?[0] else { return }
        let count = min(Int(output.frameLength), 960000 - samples.count)
        var squares = 0.0
        for i in 0..<count { let sample = channel[i]; samples.append(sample); squares += Double(sample) * Double(sample) }
        rms = count == 0 ? 0 : sqrt(squares / Double(count)) / 32767
    }
}

protocol CaptureEngine {
    var active: Bool { get }
    var meter: (level: Double, duration: Double) { get }
    func startSession() async throws
    func begin() throws
    func finish() throws -> (wav: Data, duration: Double)
    func cancel()
    func stopSession()
}
final class AudioCapture: CaptureEngine {
    private var engine: AVAudioEngine?
    private var accumulator: AudioAccumulator?
    var active: Bool { engine?.isRunning == true }
    var meter: (level: Double, duration: Double) { accumulator?.state() ?? (0,0) }
    func startSession() async throws {
        guard await AVAudioApplication.requestRecordPermission() else {
            throw APIError(code: "MICROPHONE_DENIED", message: "Allow microphone access in iPhone Settings to dictate.")
        }
        try Task.checkCancellation()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetoothHFP, .mixWithOthers])
        try session.setActive(true)
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let accumulator = try AudioAccumulator(input: input.outputFormat(forBus: 0))
        input.installTap(onBus: 0, bufferSize: 1024, format: nil) { buffer, _ in accumulator.ingest(buffer) }
        do { try engine.start() }
        catch { input.removeTap(onBus: 0); try? session.setActive(false, options: .notifyOthersOnDeactivation); throw error }
        self.engine = engine; self.accumulator = accumulator
    }
    func begin() throws {
        guard active else { throw APIError(code: "MICROPHONE_INACTIVE", message: "Open WoVoice and start a new keyboard session.") }
        accumulator?.begin()
    }
    func finish() throws -> (wav: Data, duration: Double) {
        let samples = accumulator?.finish() ?? []
        var signal = SpeechSignal()
        for start in stride(from: 0, to: samples.count, by: 320) { signal.observe(Array(samples[start..<min(start+320,samples.count)])) }
        guard signal.containsSpeech else { throw APIError(code: "NO_SPEECH", message: "No speech detected. Please try again.") }
        return (WAV.encode(samples), Double(samples.count)/16000)
    }
    func cancel() { _ = accumulator?.finish() }
    func stopSession() {
        engine?.inputNode.removeTap(onBus: 0); engine?.stop(); engine = nil; accumulator = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
