//go:build !(android || linux)

package mobile

import "os"

func getTunnelName(fd int32) (string, error) {
	return "", os.ErrInvalid
}
