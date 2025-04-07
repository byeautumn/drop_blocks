# upload through curl
qiangao@qiangao-mac ~ % curl -F "file=@/Users/qiangao/Downloads/Chua_pack.zip" http://localhost:8080/upload
/download/ff77e4c3-58e3-445a-b464-dc9f8b4aae5b%

# download through curl
qiangao@qiangao-mac ~ % curl -OJ http://localhost:8080/download/ff77e4c3-58e3-445a-b464-dc9f8b4aae5b
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100  199M  100  199M    0     0   206M      0 --:--:-- --:--:-- --:--:--  206M