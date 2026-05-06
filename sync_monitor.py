"""Monitor sync progress - run periodically to check status"""
import urllib.request, json, time, sys

def check():
    try:
        r = json.loads(urllib.request.urlopen('http://localhost:8080/api/backtest/sync-status').read())
        print(f"[{time.strftime('%H:%M:%S')}] {r['status']} - {r['progress']}/{r['total']} - {r['message']}")
        return r['status']
    except Exception as e:
        print(f"[{time.strftime('%H:%M:%S')}] Error: {e}")
        return 'error'

if __name__ == '__main__':
    interval = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    print(f"Monitoring every {interval}s. Press Ctrl+C to stop.")
    while True:
        status = check()
        if status in ('done', 'error', 'idle'):
            print("Sync finished!")
            break
        time.sleep(interval)
