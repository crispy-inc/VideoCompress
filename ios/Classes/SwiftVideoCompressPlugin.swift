import Flutter
import AVFoundation
import ffmpegkit

public class SwiftVideoCompressPlugin: NSObject, FlutterPlugin {
    private let channelName = "video_compress"
    private var session: FFmpegSession?
    private let channel: FlutterMethodChannel
    private let avController = AvController()
    
    init(channel: FlutterMethodChannel) {
        self.channel = channel
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "video_compress", binaryMessenger: registrar.messenger())
        let instance = SwiftVideoCompressPlugin(channel: channel)
        registrar.addMethodCallDelegate(instance, channel: channel)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let args = call.arguments as? Dictionary<String, Any>
        switch call.method {
        case "getByteThumbnail":
            let path = args!["path"] as! String
            let quality = args!["quality"] as! NSNumber
            let position = args!["position"] as! NSNumber
            getByteThumbnail(path, quality, position, result)
        case "getFileThumbnail":
            let path = args!["path"] as! String
            let quality = args!["quality"] as! NSNumber
            let position = args!["position"] as! NSNumber
            getFileThumbnail(path, quality, position, result)
        case "getMediaInfo":
            let path = args!["path"] as! String
            getMediaInfo(path, result)
        case "compressVideo":
            let path = args!["path"] as! String
            let quality = args!["quality"] as! NSNumber
            let deleteOrigin = args!["deleteOrigin"] as! Bool
            let startTime = args!["startTime"] as? Double
            let duration = args!["duration"] as? Double
            let includeAudio = args!["includeAudio"] as? Bool
            let frameRate = args!["frameRate"] as? Int
            let bitrate = args!["bitrate"] as? Int
            let orientation = args!["orientation"] as? Int
            compressVideo(path, quality, deleteOrigin, startTime, duration, includeAudio,
                          frameRate, bitrate, orientation, result)
        case "cancelCompression":
            cancelCompression(result)
        case "deleteAllCache":
            Utility.deleteFile(Utility.basePath(), clear: true)
            result(true)
        case "setLogLevel":
            result(true)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    private func getBitMap(_ path: String,_ quality: NSNumber,_ position: NSNumber,_ result: FlutterResult)-> Data?  {
        let url = Utility.getPathUrl(path)
        let asset = avController.getVideoAsset(url)
        guard let track = avController.getTrack(asset) else { return nil }
        
        let assetImgGenerate = AVAssetImageGenerator(asset: asset)
        assetImgGenerate.appliesPreferredTrackTransform = true
        
        let timeScale = CMTimeScale(track.nominalFrameRate)
        let time = CMTimeMakeWithSeconds(Float64(truncating: position),preferredTimescale: timeScale)
        guard let img = try? assetImgGenerate.copyCGImage(at:time, actualTime: nil) else {
            return nil
        }
        let thumbnail = UIImage(cgImage: img)
        let compressionQuality = CGFloat(0.01 * Double(truncating: quality))
        return thumbnail.jpegData(compressionQuality: compressionQuality)
    }
    
    private func getByteThumbnail(_ path: String,_ quality: NSNumber,_ position: NSNumber,_ result: FlutterResult) {
        if let bitmap = getBitMap(path,quality,position,result) {
            result(bitmap)
        }
    }
    
    private func getFileThumbnail(_ path: String,_ quality: NSNumber,_ position: NSNumber,_ result: FlutterResult) {
        let fileName = Utility.getFileName(path)
        let url = Utility.getPathUrl("\(Utility.basePath())/\(fileName).jpg")
        Utility.deleteFile(path)
        if let bitmap = getBitMap(path,quality,position,result) {
            guard (try? bitmap.write(to: url)) != nil else {
                return result(FlutterError(code: channelName,message: "getFileThumbnail error",details: "getFileThumbnail error"))
            }
            result(Utility.excludeFileProtocol(url.absoluteString))
        }
    }
    
    public func getMediaInfoJson(_ path: String)->[String : Any?] {
        let url = Utility.getPathUrl(path)
        let asset = avController.getVideoAsset(url)
        guard let track = avController.getTrack(asset) else { return [:] }
        
        let playerItem = AVPlayerItem(url: url)
        let metadataAsset = playerItem.asset
        
        let orientation = avController.getVideoOrientation(path)
        
        let title = avController.getMetaDataByTag(metadataAsset,key: "title")
        let author = avController.getMetaDataByTag(metadataAsset,key: "author")
        
        let duration = asset.duration.seconds * 1000
        let filesize = track.totalSampleDataLength
        
        let size = track.naturalSize.applying(track.preferredTransform)
        
        let width = abs(size.width)
        let height = abs(size.height)

        let hasAudio = avController.hasAudio(asset)

        let dictionary = [
            "path":Utility.excludeFileProtocol(path),
            "title":title,
            "author":author,
            "width":width,
            "height":height,
            "duration":duration,
            "filesize":filesize,
            "orientation":orientation,
            "has_audio":hasAudio
            ] as [String : Any?]
        return dictionary
    }
    
    private func getMediaInfo(_ path: String,_ result: FlutterResult) {
        let json = getMediaInfoJson(path)
        let string = Utility.keyValueToJson(json)
        result(string)
    }

    private func getComposition(_ isIncludeAudio: Bool,_ timeRange: CMTimeRange, _ sourceVideoTrack: AVAssetTrack)->AVAsset {
        let composition = AVMutableComposition()
        if !isIncludeAudio {
            let compressionVideoTrack = composition.addMutableTrack(withMediaType: AVMediaType.video, preferredTrackID: kCMPersistentTrackID_Invalid)
            compressionVideoTrack!.preferredTransform = sourceVideoTrack.preferredTransform
            try? compressionVideoTrack!.insertTimeRange(timeRange, of: sourceVideoTrack, at: CMTime.zero)
        } else {
            return sourceVideoTrack.asset!
        }
        
        return composition
    }

    private func compressVideo(_ path: String,
                               _ quality: NSNumber,
                               _ deleteOrigin: Bool,
                               _ startTime: Double?,
                               _ duration: Double?,
                               _ includeAudio: Bool?,
                               _ frameRate: Int?,
                               _ bitrate: Int?,
                               _ orientation: Int?,
                               _ result: @escaping FlutterResult
    ) {
        if session != nil {
            // TODO: エラー
            return
        }
        let sourceVideoUrl = Utility.getPathUrl(path)
        let sourceVideoAsset = avController.getVideoAsset(sourceVideoUrl)
        let sourceVideoDuration = sourceVideoAsset.duration.seconds * 1000
        let compressionUrl =
            Utility.getPathUrl("\(Utility.basePath())/\(Utility.getFileName(path)).mp4")
        print("startCompressVideo3: \(compressionUrl.path)")
        let info = getMediaInfoJson(path)
        print(info)
        let width = (info["width"] as? CGFloat) ?? 0
        let height = (info["height"] as? CGFloat) ?? 0

        let originalSize = CGSize(width: width, height: height)

        var commands = [String]()
        if let startTime = startTime {
            commands.append(contentsOf: [
                "-ss",
                "\(startTime / 1000)",
            ])
        }
        commands.append(contentsOf: [
            "-i",
            path,
        ])
        let totalDuration: Double
        if let duration = duration {
            commands.append(contentsOf: [
                "-t",
                "\(duration / 1000)",
            ])
            totalDuration = duration
        } else {
            totalDuration = sourceVideoDuration
        }
        if includeAudio == false {
            commands.append(contentsOf: [
                "-an"
            ])
        }
        var filters = [String]()
        if let frameRate = frameRate {
            filters.append("framerate=\(frameRate)")
        }
        if let roteteFilters = orientation?.rotateFilters, !roteteFilters.isEmpty {
            filters.append(contentsOf: roteteFilters)
        }
        commands.append(contentsOf: [
            "-s",
            originalSize.getOutputSizeString(orientation ?? 0, quality: quality),
        ])
        if !filters.isEmpty {
            commands.append(contentsOf: [
                "-vf",
                filters.joined(separator: ",")
            ])
        }
        if let bitrate = bitrate {
            commands.append(contentsOf: [
                "-b:v",
                "\(bitrate)"
            ])
        }
        commands.append(contentsOf: [
            "-c:v",
            "h264_videotoolbox",
            compressionUrl.path,
        ])

        Utility.deleteFile(compressionUrl.path, clear: true)
        session = FFmpegKit.execute(
            withArgumentsAsync: commands,
            withCompleteCallback: { [weak self] session in
                guard let self = self else {
                    return
                }
                self.session = nil
                guard let session = session else {
                    return
                }
                if ReturnCode.isSuccess(session.getReturnCode()) {
                    var json = self.getMediaInfoJson(Utility.excludeEncoding(compressionUrl.path))
                    json["isCancel"] = false
                    let jsonString = Utility.keyValueToJson(json)
                    result(jsonString)
                } else if ReturnCode.isCancel(session.getReturnCode()) {
                    var json = self.getMediaInfoJson(path)
                    json["isCancel"] = true
                    let jsonString = Utility.keyValueToJson(json)
                    result(jsonString)
                }
            },
            withLogCallback: { log in
//                print(log?.getMessage())
            },
            withStatisticsCallback: { [weak self] statistics in
                guard let self = self, let statistics = statistics else {
                    return
                }
                if(self.session != nil) {
                    let time = Double(statistics.getTime())
//                    print("time: \(time), totalDuration: \(totalDuration)(videoDuration: \(sourceVideoDuration))")
                    let progress = time / totalDuration * 100
                    self.channel.invokeMethod("updateProgress", arguments: "\(String(describing: progress > 100 ? 100 : progress))")
                }
            }
        )
    }
    
    private func cancelCompression(_ result: FlutterResult) {
        session = nil
        FFmpegKit.cancel()
        result("")
    }
    
}


extension CGSize {
    func getOutputSize(_ orientation: Int, quality: NSNumber) -> CGSize {
        let size = getOutputSizeByQuality(quality)
        return size.fromOrientation(orientation)
    }

    func getOutputSizeByQuality(_ quality: NSNumber) -> CGSize {
        switch (quality) {
        case 1:
            return atMostResize(360)
        case 2:
            return atMostResize(640)
        case 3:
            return atMostResize(1280, 720)
        case 4:
            return atMostResize(640, 480)
        case 5:
            return atMostResize(940, 560)
        case 6:
            return atMostResize(1280, 720)
        case 7:
            return atMostResize(1920, 1080)
        default:
            return atMostResize(720)
        }
    }

    func getOutputSizeString(_ orientation: Int, quality: NSNumber) -> String {
        let size = getOutputSize(orientation, quality: quality)
        let width = Int(size.width.toOutputSize())
        let height = Int(size.height.toOutputSize())
        return "\(width)*\(height)"
    }

    func fromOrientation(_ orientation: Int) -> CGSize {
        if orientation == 90 || orientation == 270 {
            return CGSize(width: height, height: width)
        }
        return self
    }

    func atMostResize(_ atMiner: CGFloat) -> CGSize {
        return atMostResize(CGFloat.greatestFiniteMagnitude, atMiner)
    }

    func atMostResize(_ atMajor: CGFloat, _ atMiner: CGFloat) -> CGSize {
        let major = majorSize
        let miner = minerSize
        if miner < atMiner, major < atMajor {
            return self
        }
        let minorScale = minerSize / CGFloat(atMiner)
        let majorScale = majorSize / CGFloat(atMajor)

        let inputRatio = minerSize / majorSize
        if majorScale >= minorScale {
            return replace(CGSize(width: atMajor, height: atMajor * inputRatio))
        }
        return replace(CGSize(width: atMiner, height: atMiner / inputRatio))
    }

    func replace(_ size: CGSize) -> CGSize {
        if (width > height) {
            return CGSize(width: size.majorSize, height: size.minerSize)
        }
        return CGSize(width: size.minerSize, height: size.majorSize)
    }

    var majorSize: CGFloat {
        return max(height, width)
    }

    var minerSize: CGFloat {
        return min(height, width)
    }
}

extension Int {
    var rotateFilters: [String] {
        if self == 90 {
            return [
                "transpose=1"
            ]
        } else if self == 270 {
            return [
                "transpose=2"
            ]
        } else if self == 180 {
            return [
                "hflip",
                "vflip"
            ]
        }
        return []
    }
}

extension CGFloat {
    func toOutputSize() -> Int {
        let num = Int(ceil(self))
        if num % 2 == 0 {
            return num
        }
        return num - 1
    }
}
