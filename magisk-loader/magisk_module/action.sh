API=$(getprop ro.build.version.sdk)
if [ "$API" -ge 29 ]; then
  am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733 android
else
  am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://5776733 android
fi