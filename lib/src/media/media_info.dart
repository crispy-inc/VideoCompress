import 'dart:io';

class MediaInfo {
  String? path;
  String? title;
  String? author;
  int? width;
  int? height;
  bool? hasAudio;

  /// [Android] API level 17
  int? orientation;

  /// bytes
  int? filesize; // filesize
  /// microsecond
  double? duration;
  bool? isCancel;
  File? file;
  int? bitrate;

  MediaInfo({
    required this.path,
    this.title,
    this.author,
    this.width,
    this.height,
    this.hasAudio,
    this.orientation,
    this.filesize,
    this.duration,
    this.isCancel,
    this.file,
    this.bitrate,
  });

  MediaInfo.fromJson(Map<String, dynamic> json) {
    print(json);
    path = json['path'];
    title = json['title'];
    author = json['author'];
    width = json['width'];
    height = json['height'];
    hasAudio = json['has_audio'];
    orientation = json['orientation'];
    filesize = json['filesize'];
    duration = double.tryParse('${json['duration']}');
    isCancel = json['isCancel'];
    file = File(path!);
    bitrate = json['bitrate'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = Map<String, dynamic>();
    data['path'] = this.path;
    data['title'] = this.title;
    data['author'] = this.author;
    data['width'] = this.width;
    data['height'] = this.height;
    data['has_audio'] = this.hasAudio;
    if (this.orientation != null) {
      data['orientation'] = this.orientation;
    }
    data['filesize'] = this.filesize;
    data['duration'] = this.duration;
    if (this.isCancel != null) {
      data['isCancel'] = this.isCancel;
    }
    data['file'] = File(path!).toString();
    data['bitrate'] = this.bitrate;
    return data;
  }
}
