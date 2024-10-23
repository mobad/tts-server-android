#!/bin/bash

cd $GITHUB_WORKSPACE/lib-gojni-aar/tts-server-lib

go install golang.org/x/mobile/cmd/gomobile
gomobile init
go get golang.org/x/mobile/bind
gomobile bind -ldflags "-s -w" -v -androidapi=21
mv -f *.aar ../
