import re

with open('/home/ganeshak11/AndroidStudioProjects/FortisMobile/app/src/main/java/com/example/fortismobile/MainActivity.kt', 'r') as f:
    content = f.read()

content = re.sub(r'val\s+client\s*=\s*AgentClient\.getClient\(.*?\)', 'val client = FortisApiClient.getClient()', content)

def replace_builder(match):
    path = match.group(1)
    chain = match.group(2)
    if chain.strip():
        return f'FortisApiClient.buildRequest("{path}") {{ {chain.strip()[1:].strip()} }}'
    else:
        return f'FortisApiClient.buildRequest("{path}")'

content = re.sub(r'Request\.Builder\(\)\s*\.url\("http://\$ip:8023([^"]+)"\)(.*?)\.build\(\)', replace_builder, content, flags=re.DOTALL)

with open('/home/ganeshak11/AndroidStudioProjects/FortisMobile/app/src/main/java/com/example/fortismobile/MainActivity.kt', 'w') as f:
    f.write(content)
