#!/usr/bin/env bash
# 端到端冒烟验证：预约 → 派单 → 扫码核验 → 称重 → 运输 → 二次称重 → 合规闭环 → 结算
# 以及主要异常场景。前提：应用已在 localhost:8080 运行。
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

# 解析 JSON 字段
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

req() { # method path role account body
  curl -s -X "$1" "$BASE$2" -H "X-Role: $3" -H "X-Account-Id: $4" \
       -H "Content-Type: application/json" ${5:+-d "$5"}
}

check() { # 描述 实际值 期望值
  if [ "$2" == "$3" ]; then PASS=$((PASS+1)); echo "  ✔ $1 ($2)";
  else FAIL=$((FAIL+1)); echo "  ✘ $1 期望[$3] 实际[$2]"; fi
}

tomorrow() { python3 -c "
from datetime import datetime,timedelta,timezone
d=(datetime.now(timezone.utc)+timedelta(days=1)).replace(hour=$1,minute=$2,second=0,microsecond=0)
# 明天北京时间 $1:$2 = UTC $1-8:$2
print(d.isoformat().replace('+00:00','Z'))"; }
# 北京时间明天 10:00 / 11:00（UTC 02:00 / 03:00）
WS=$(tomorrow 2 0); WE=$(tomorrow 3 0)

echo "=== 1. 主流程：预约→派单→核验→称重→运输→二次称重→闭环→结算 ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-001/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":2,\"estimatedWeightKg\":300,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
echo "  预约 ID=$AID"
DID=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\",\"routeNote\":\"春熙路→经开区\"}")")
echo "  派单 ID=$DID"
req POST /api/dispatches/$DID/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID/arrive DRIVER driver-1 '{}' >/dev/null
V=$(req POST /api/dispatches/$DID/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-001","handoverName":"王店长","photoUrls":"https://img.example.com/site1.jpg"}')
check "扫码核验后进入装车" "$(jget "['data']['status']" <<< "$V")" "LOADING"
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID,\"barrelCode\":\"BAR-001\",\"weightKg\":150,\"deviceCode\":\"DEV-001\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w1.jpg\",\"handoverName\":\"王店长\"}" >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID,\"barrelCode\":\"BAR-002\",\"weightKg\":148,\"deviceCode\":\"DEV-001\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w2.jpg\",\"handoverName\":\"王店长\"}" >/dev/null
req POST /api/dispatches/$DID/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID DRIVER driver-1 '{"points":[{"lat":30.66,"lng":104.06},{"lat":30.60,"lng":104.10}]}' >/dev/null
req POST /api/dispatches/$DID/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID: print(m['id']); break")
echo "  联单 ID=$MID"
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID,\"arrivalWeightKg\":296,\"barrelCount\":2,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
DETAIL=$(req GET /api/manifests/$MID REGULATOR regulator-1)
check "联单闭环 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$DETAIL")" "CLOSED"
check "合规结果 PASS" "$(jget "['data']['manifest']['complianceResult']" <<< "$DETAIL")" "PASS"
check "出店重量 298kg" "$(jget "['data']['manifest']['exitWeightKg']" <<< "$DETAIL")" "298.0"
check "到厂重量 296kg" "$(jget "['data']['manifest']['arrivalWeightKg']" <<< "$DETAIL")" "296.0"
check "结算金额 947.2 元" "$(jget "['data']['settlement']['amount']" <<< "$DETAIL")" "947.2"
check "称重记录 2 条" "$(jget "['data']['weighings'].__len__()" <<< "$DETAIL")" "2"
check "轨迹点 2 个" "$(jget "['data']['tracks'].__len__()" <<< "$DETAIL")" "2"

echo "=== 2. 一桶一码追溯 ==="
TRACE=$(req GET /api/barrels/BAR-001/trace REGULATOR regulator-1)
check "BAR-001 已到厂" "$(jget "['data']['barrel']['status']" <<< "$TRACE")" "AT_PLANT"
check "BAR-001 有称重档案" "$(jget "['data']['weighingRecords'].__len__()" <<< "$TRACE")" "1"

echo "=== 3. 商户质疑重量 → 冻结结算 → 协商调整 ==="
DIS=$(req POST /api/disputes MERCHANT merchant-1 "{\"manifestId\":$MID,\"reason\":\"对运输损耗 2kg 有异议\"}")
DISID=$(jget "['data']['id']" <<< "$DIS")
check "质疑后联单 DISPUTED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID MERCHANT merchant-1)")" "DISPUTED"
check "结算冻结 FROZEN" "$(jget "['data']['status']" <<< "$(req GET /api/settlements/by-manifest/$MID MERCHANT merchant-1)")" "FROZEN"
req POST /api/disputes/$DISID/resolve RECYCLER recycler-1 '{"resolution":"双方复核后按 297kg 结算","adjustedWeightKg":297}' >/dev/null
check "解决后联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID MERCHANT merchant-1)")" "CLOSED"
check "调整后金额 950.4 元" "$(jget "['data']['amount']" <<< "$(req GET /api/settlements/by-manifest/$MID MERCHANT merchant-1)")" "950.4"

echo "=== 4. 商户临时取消 ==="
AID2=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID2=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID2,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/appointments/$AID2/cancel MERCHANT merchant-1 '{"reason":"今日店休"}' >/dev/null
check "预约已取消" "$(jget "['data']['status']" <<< "$(req GET /api/appointments/$AID2 MERCHANT merchant-1)")" "CANCELLED"
check "派单联动取消" "$(jget "['data']['status']" <<< "$(req GET /api/dispatches/$DID2 RECYCLER recycler-1)")" "CANCELLED"
check "取消异常工单" "$(req GET "/api/exceptions/by-ref?refType=APPOINTMENT&refId=$AID2" RECYCLER recycler-1 | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['type'])")" "MERCHANT_CANCEL"

echo "=== 5. 手动补录（设备故障） ==="
AID3=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID3=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID3,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID3/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID3/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID3/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-001","handoverName":"王店长"}' >/dev/null
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/weighings" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DID3,\"barrelCode\":\"BAR-003\",\"weightKg\":120,\"source\":\"MANUAL\"}")
check "补录无原因被拒(400)" "$CODE" "400"
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID3,\"barrelCode\":\"BAR-003\",\"weightKg\":120,\"source\":\"MANUAL\",\"note\":\"设备故障人工复核\",\"photoUrl\":\"https://img.example.com/m1.jpg\"}" >/dev/null
MID3=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID3: print(m['id']); break")
check "联单记录补录 1 条" "$(jget "['data']['manifest']['manualWeighCount']" <<< "$(req GET /api/manifests/$MID3 REGULATOR regulator-1)")" "1"

echo "=== 6. 处置厂拒收（混入厨余） ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-002/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID4=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":2,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID4=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID4,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID4/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4/verify DRIVER driver-1 '{"storeQr":"STORE-QR-2","deviceCode":"DEV-002","handoverName":"陈店长"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID4,\"barrelCode\":\"BAR-101\",\"weightKg\":100,\"deviceCode\":\"DEV-002\",\"source\":\"DEVICE\"}" >/dev/null
req POST /api/dispatches/$DID4/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID4 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID4/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID4=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID4: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID4,\"arrivalWeightKg\":100,\"barrelCount\":1,\"qualityCheck\":\"CONTAMINATED\",\"decision\":\"REJECT\",\"rejectReason\":\"混入大量厨余垃圾\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "联单 REJECTED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID4 REGULATOR regulator-1)")" "REJECTED"

echo "=== 7. 运输损耗异常 → 阻断闭环 → 处理后恢复 ==="
AID5=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":2,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID5=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID5,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID5/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID5/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID5/verify DRIVER driver-1 '{"storeQr":"STORE-QR-2","deviceCode":"DEV-002","handoverName":"陈店长"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID5,\"barrelCode\":\"BAR-102\",\"weightKg\":100,\"deviceCode\":\"DEV-002\",\"source\":\"DEVICE\"}" >/dev/null
req POST /api/dispatches/$DID5/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID5 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID5/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID5=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID5: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID5,\"arrivalWeightKg\":80,\"barrelCount\":1,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "损耗 20% 联单 EXCEPTION" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID5 REGULATOR regulator-1)")" "EXCEPTION"
TID=$(req GET "/api/exceptions/by-ref?refType=MANIFEST&refId=$MID5" RECYCLER recycler-1 | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['type']=='TRANSPORT_LOSS_ABNORMAL': print(t['id']); break")
req POST /api/exceptions/$TID/resolve RECYCLER recycler-1 '{"resolution":"确认为阀门渗漏，已维修备案"}' >/dev/null
check "工单处理后备联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID5 REGULATOR regulator-1)")" "CLOSED"

echo "=== 8. 监管抽查 + 风险名单 + 报表 ==="
req POST /api/inspections REGULATOR regulator-1 "{\"manifestId\":$MID,\"result\":\"PASS\",\"note\":\"抽查合格\"}" >/dev/null
req POST /api/risks/scan REGULATOR regulator-1 '{}' >/dev/null
RISKS=$(req GET /api/risks REGULATOR regulator-1)
check "风险名单非空" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$RISKS")" "True"
EFF=$(req GET /api/reports/vehicle-efficiency RECYCLER recycler-1)
check "车辆效率报表" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$EFF")" "True"
CERT=$(req GET /api/reports/stores/1/certificate MERCHANT merchant-1)
check "商户回收证明联单数≥1" "$(python3 -c "import sys,json;print(json.load(sys.stdin)['data']['manifestCount']>=1)" <<< "$CERT")" "True"
TREND=$(req GET /api/reports/regulator/trend REGULATOR regulator-1)
check "监管趋势报表" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$TREND")" "True"

echo "=== 9. 称重设备离线补录 → 待复核 → 复核通过打人工标记 → 闭环结算 ==="
# DEV-004 为从未心跳的离线蓝牙秤（门店1）
AID6=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":2,\"estimatedWeightKg\":300,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID6=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID6,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID6/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID6/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID6/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-004","handoverName":"王店长"}' >/dev/null
# 在线设备不能走离线补录
ONLINE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/supplements" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DID6,\"deviceCode\":\"DEV-001\",\"items\":[{\"barrelCode\":\"BAR-005\",\"weightKg\":150}],\"photoUrls\":\"x.jpg\",\"reason\":\"r\"}")
check "在线设备补录被拒(409)" "$ONLINE_CODE" "409"
# 无照片被拒
NOPHOTO_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/supplements" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DID6,\"deviceCode\":\"DEV-004\",\"items\":[{\"barrelCode\":\"BAR-005\",\"weightKg\":150}],\"photoUrls\":\"\",\"reason\":\"蓝牙连不上\"}")
check "补录无照片被拒(400)" "$NOPHOTO_CODE" "400"
SUP=$(req POST /api/supplements DRIVER driver-1 "{\"dispatchId\":$DID6,\"deviceCode\":\"DEV-004\",\"items\":[{\"barrelCode\":\"BAR-005\",\"weightKg\":150,\"handoverName\":\"王店长\"},{\"barrelCode\":\"BAR-006\",\"weightKg\":146,\"handoverName\":\"王店长\"}],\"photoUrls\":\"https://img.example.com/s1.jpg,https://img.example.com/s2.jpg\",\"reason\":\"到店蓝牙秤无法连接，拍照手录\"}")
SID=$(jget "['data']['id']" <<< "$SUP")
check "补录单待复核" "$(jget "['data']['status']" <<< "$SUP")" "PENDING_REVIEW"
check "补录申报总重 296kg" "$(jget "['data']['declaredWeightKg']" <<< "$SUP")" "296"
MID6=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID6: print(m['id']); break")
D6=$(req GET /api/manifests/$MID6 REGULATOR regulator-1)
check "联单进入待复核" "$(jget "['data']['manifest']['status']" <<< "$D6")" "PENDING_REVIEW"
check "复核前重量不采信(0kg)" "$(jget "['data']['manifest']['exitWeightKg']" <<< "$D6")" "0.0"
# 待复核禁止起运
LC_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/dispatches/$DID6/load-complete" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d '{}')
check "待复核禁止起运(409)" "$LC_CODE" "409"
# 复核台视图含照片/历史重量/补录频次
RV=$(req GET /api/supplements/$SID/review-view REGULATOR regulator-1)
check "复核台照片 2 张" "$(jget "['data']['photoUrls'].__len__()" <<< "$RV")" "2"
check "复核台申报桶均重 148kg" "$(jget "['data']['declaredAvgWeightPerBarrel']" <<< "$RV")" "148.0"
check "复核台可见司机信用 100" "$(jget "['data']['driver']['creditScore']" <<< "$RV")" "100"
# 照片不清晰却通过 → 拒绝
BAD_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/supplements/$SID/review" -H "X-Role: RECYCLER" -H "Content-Type: application/json" -d '{"decision":"APPROVED","photoClear":false,"barrelCountMatch":true}')
check "照片不清晰通过被拒(400)" "$BAD_CODE" "400"
req POST /api/supplements/$SID/review RECYCLER recycler-1 '{"decision":"APPROVED","photoClear":true,"barrelCountMatch":true,"comment":"秤面读数清晰，桶数相符"}' >/dev/null
D6B=$(req GET /api/manifests/$MID6 REGULATOR regulator-1)
check "复核通过联单恢复" "$(jget "['data']['manifest']['status']" <<< "$D6B")" "CREATED"
check "人工补录标记已打" "$(jget "['data']['manifest']['manualSupplementFlag']" <<< "$D6B")" "True"
check "复核后采信重量 296kg" "$(jget "['data']['manifest']['exitWeightKg']" <<< "$D6B")" "296.0"
# 正常运输收料闭环结算
req POST /api/dispatches/$DID6/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID6 DRIVER driver-1 '{"points":[{"lat":30.66,"lng":104.06}]}' >/dev/null
req POST /api/dispatches/$DID6/arrive-plant DRIVER driver-1 '{}' >/dev/null
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID6,\"arrivalWeightKg\":294,\"barrelCount\":2,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
D6C=$(req GET /api/manifests/$MID6 REGULATOR regulator-1)
check "补录联单闭环 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$D6C")" "CLOSED"
check "补录联单结算 940.8 元" "$(jget "['data']['settlement']['amount']" <<< "$D6C")" "940.8"
# 监管抽查优先查看人工补录批次
PRIO=$(req GET "/api/manifests?manualSupplement=true" REGULATOR regulator-1)
check "抽查优先列表含该联单" "$(python3 -c "import sys,json;print(any(m['id']==$MID6 for m in json.load(sys.stdin)['data']))" <<< "$PRIO")" "True"

echo "=== 10. 离线补录复核驳回 → 回滚重量/油桶退回/扣信用/留痕 ==="
AID7=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID7=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID7,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID7/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID7/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID7/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-004","handoverName":"王店长"}' >/dev/null
SUP2=$(req POST /api/supplements DRIVER driver-1 "{\"dispatchId\":$DID7,\"deviceCode\":\"DEV-004\",\"items\":[{\"barrelCode\":\"BAR-004\",\"weightKg\":95}],\"photoUrls\":\"https://img.example.com/blur.jpg\",\"reason\":\"蓝牙秤离线\"}")
SID2=$(jget "['data']['id']" <<< "$SUP2")
MID7=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID7: print(m['id']); break")
req POST /api/supplements/$SID2/review RECYCLER recycler-1 '{"decision":"REJECTED","photoClear":false,"barrelCountMatch":false,"comment":"照片模糊无法辨认读数，桶数不符"}' >/dev/null
D7=$(req GET /api/manifests/$MID7 REGULATOR regulator-1)
check "驳回后联单恢复 CREATED" "$(jget "['data']['manifest']['status']" <<< "$D7")" "CREATED"
check "驳回后重量回滚 0kg" "$(jget "['data']['manifest']['exitWeightKg']" <<< "$D7")" "0.0"
check "驳回后未打人工标记" "$(jget "['data']['manifest']['manualSupplementFlag']" <<< "$D7")" "False"
check "油桶退回在店" "$(jget "['data']['barrel']['status']" <<< "$(req GET /api/barrels/BAR-004/trace REGULATOR regulator-1)")" "AT_STORE"
check "司机信用扣减至 80" "$(jget "['data']['creditScore']" <<< "$(req GET /api/supplements/drivers/1 RECYCLER recycler-1)")" "80"
check "驳回异常工单留痕" "$(req GET "/api/exceptions/by-ref?refType=MANIFEST&refId=$MID7" REGULATOR regulator-1 | python3 -c "import sys,json;print(any(t['type']=='SUPPLEMENT_REJECTED' for t in json.load(sys.stdin)['data']))")" "True"

echo "=== 11. 频繁设备离线 → 设备检查工单 + 限制司机补录 → 核查解除 ==="
submit_supp3() { # 派单并提交一次 DEV-006 离线补录，返回 dispatchId
  local BAR=$1
  local A=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":3,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
  local D=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$A,\"vehicleId\":2,\"driverId\":2,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
  req POST /api/dispatches/$D/depart DRIVER driver-2 '{}' >/dev/null
  req POST /api/dispatches/$D/arrive DRIVER driver-2 '{}' >/dev/null
  req POST /api/dispatches/$D/verify DRIVER driver-2 '{"storeQr":"STORE-QR-3","deviceCode":"DEV-006","handoverName":"刘老板"}' >/dev/null
  req POST /api/supplements DRIVER driver-2 "{\"dispatchId\":$D,\"deviceCode\":\"DEV-006\",\"items\":[{\"barrelCode\":\"$BAR\",\"weightKg\":80}],\"photoUrls\":\"https://img.example.com/$BAR.jpg\",\"reason\":\"设备无响应\"}" >/dev/null
  echo "$D"
}
submit_supp3 BAR-203 >/dev/null
submit_supp3 BAR-204 >/dev/null
submit_supp3 BAR-205 >/dev/null
check "3 次离线后司机被限录" "$(jget "['data']['manualBlocked']" <<< "$(req GET /api/supplements/drivers/2 RECYCLER recycler-1)")" "True"
check "设备检查工单已生成" "$(req GET "/api/exceptions/by-ref?refType=DEVICE&refId=6" REGULATOR regulator-1 | python3 -c "import sys,json;print(any(t['type']=='DEVICE_CHECK' for t in json.load(sys.stdin)['data']))")" "True"
DBLOCK=$(submit_supp3 BAR-206)
BLOCK_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/supplements" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DBLOCK,\"deviceCode\":\"DEV-006\",\"items\":[{\"barrelCode\":\"BAR-206\",\"weightKg\":80}],\"photoUrls\":\"https://img.example.com/x.jpg\",\"reason\":\"设备无响应\"}")
# submit_supp3 内部已尝试一次（被限录），这里再次确认 409；直接用其返回码
check "限录后补录被拒(409)" "$BLOCK_CODE" "409"
req POST /api/supplements/drivers/2/unblock RECYCLER recycler-1 '{"comment":"已更换蓝牙模块并复测正常"}' >/dev/null
check "核查设备后解除限录" "$(jget "['data']['manualBlocked']" <<< "$(req GET /api/supplements/drivers/2 RECYCLER recycler-1)")" "False"
UNBLOCK_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/supplements" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DBLOCK,\"deviceCode\":\"DEV-006\",\"items\":[{\"barrelCode\":\"BAR-206\",\"weightKg\":80}],\"photoUrls\":\"https://img.example.com/x.jpg\",\"reason\":\"设备无响应\"}")
check "解除后恢复补录(200)" "$UNBLOCK_CODE" "200"

echo "=== 12. Kafka 事件审计流水 ==="
AUDIT=$(psql "postgresql://wasteoil:wasteoil123@localhost:5433/wasteoil" -tAc "SELECT count(*) FROM audit_logs" 2>/dev/null || echo "0")
check "审计流水已落库(>20 条)" "$(python3 -c "print($AUDIT>20)")" "True"

echo ""
echo "=========================================="
echo "冒烟结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" == "0" ] && echo "SMOKE_OK" || exit 1
