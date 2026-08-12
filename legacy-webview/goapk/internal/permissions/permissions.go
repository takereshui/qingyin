// Package permissions maps W3C Permissions API names to Android manifest permissions.
//
// Web apps declare capabilities using standardized names from the Permissions API
// (https://www.w3.org/TR/permissions/) and the Permissions Policy spec. PWA manifest
// files increasingly include a "permissions" array with these names. This package
// translates them to the corresponding android.permission.* strings needed in
// AndroidManifest.xml.
package permissions

import "fmt"

// webToAndroid maps W3C Permissions API names to Android permission strings.
// A single web permission may require multiple Android permissions.
var webToAndroid = map[string][]string{
	"geolocation":        {"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"},
	"camera":             {"android.permission.CAMERA"},
	"microphone":         {"android.permission.RECORD_AUDIO"},
	"notifications":      {"android.permission.POST_NOTIFICATIONS"},
	"background-sync":    {"android.permission.WAKE_LOCK"},
	"nfc":                {"android.permission.NFC"},
	"bluetooth":          {"android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"},
	"persistent-storage": {}, // no Android permission needed
	"clipboard-read":     {}, // no Android permission needed on modern Android
	"clipboard-write":    {}, // no Android permission needed on modern Android
	// Native DownloadManager writes to the public Music directory. Android 10+
	// grants this through scoped storage; Android 9 and lower still need WRITE.
	"music-downloads": {"android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.POST_NOTIFICATIONS"},
	// Native MediaStore scanning needs the granular Android 13+ audio permission,
	// with READ_EXTERNAL_STORAGE retained for Android 7–12 compatibility.
	"local-music-library": {"android.permission.READ_MEDIA_AUDIO", "android.permission.READ_EXTERNAL_STORAGE"},
}

// Resolve translates a list of web permission names into a deduplicated list of
// Android permission strings. INTERNET is always included.
// Returns an error if any web permission name is unrecognized.
func Resolve(webPerms []string) ([]string, error) {
	seen := map[string]bool{"android.permission.INTERNET": true}
	result := []string{"android.permission.INTERNET"}

	for _, wp := range webPerms {
		androidPerms, ok := webToAndroid[wp]
		if !ok {
			return nil, fmt.Errorf("unknown web permission %q; known: %s", wp, knownNames())
		}
		for _, ap := range androidPerms {
			if !seen[ap] {
				seen[ap] = true
				result = append(result, ap)
			}
		}
	}
	return result, nil
}

// Known returns the sorted list of recognized web permission names.
func Known() []string {
	names := make([]string, 0, len(webToAndroid))
	for k := range webToAndroid {
		names = append(names, k)
	}
	return names
}

func knownNames() string {
	s := ""
	for k := range webToAndroid {
		if s != "" {
			s += ", "
		}
		s += k
	}
	return s
}
