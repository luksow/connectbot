VX ConnectBot
=========

As an Emacs fanatic, I am not satisfied with support for hardware keyboards in
VX ConnectBot. Original project aims for the most universal solutions but it
results in many things simply not working correctly. I got tired of trying to
improve original keyboard support, so I threw away all the code and rewrite it
to suite my own needs. The result is not impressive - it's simple hack based
on hardcoding a lot of things, but it works like a charm!

Sadly, it will not work for you, unless you use the same tablet and bluetooth
keyboard as I do. However, you can easily tune it for your devices. Just have
a look on [TerminalKeyListener.java](https://github.com/luksow/connectbot/blob/master/src/sk/vx/connectbot/service/TerminalKeyListener.java)
and change handleMultipleKeyDown, handleKeyDown and handleKeyUp to match your
hardware.

If you have any questions, don't hesitate to contact me:
Łukasz Sowa <contact at lukaszsowa dot pl>

## Things changed
 - keyboard mappings
 - selecting region for copying works with keyboard again