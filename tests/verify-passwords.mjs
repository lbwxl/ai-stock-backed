// Local integration checks: only create/change/delete this run's disposable user.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
const base = (process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
assert.ok(['localhost','127.0.0.1','[::1]'].includes(new URL(base).hostname));
const username = `pwd_${randomBytes(5).toString('hex')}`;
const sessions = [];
let admin;
let fixtureId;
let checks = 0;
async function request(path, {token, method='GET', body}={}) {
  const response=await fetch(base+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(15000)});
  return response.json();
}
async function ok(path, options) { const result=await request(path,options); assert.equal(result.code,200,'Request failed'); return result; }
async function login(name,password) {
  const captcha=await ok('/captchaImage'); let code='';
  if(captcha.captchaEnabled!==false) {
    assert.match(captcha.uuid,/^[a-zA-Z0-9-]+$/);
    const raw=execFileSync(process.env.REDIS_CLI||'redis-cli',['--raw','-h','127.0.0.1','-p','6379','GET',`captcha_codes:${captcha.uuid}`],{encoding:'utf8'}).trim();
    try {code=String(JSON.parse(raw));}catch{code=raw;}
    assert.ok(code);
  }
  const result=await ok('/login',{method:'POST',body:{username:name,password,uuid:captcha.uuid,code}});
  assert.ok(result.token); sessions.push(result.token); return result.token;
}
async function check(name, action){await action();checks++;console.log(`PASS ${name}`);}
try {
  await check('public canonical policy endpoint',async()=>{
    const {data}=await ok('/passwordPolicy');
    assert.equal(data.minLength,15);assert.equal(data.maxLength,64);assert.equal(data.maxUtf8Bytes,72);assert.ok(data.blockedPasswords.includes('passwordpassword'));
  });
  await check('existing administrator password can still log in',async()=>{admin=await login(process.env.TEST_USERNAME||'admin',process.env.TEST_PASSWORD||'admin123');});
  await check('direct create API rejects weak, repeated, contextual and oversized passwords',async()=>{
    for(const password of [null,'short','passwordpassword',username+'123456789', 'x'.repeat(16),'ab12'.repeat(4),'汉'.repeat(25),'Abcd1234'.repeat(9)]){
      const result=await request('/system/user',{token:admin,method:'POST',body:{userName:username,nickName:username,password,status:'0',roleIds:[],postIds:[]}});
      assert.equal(result.code,400,'Password policy was bypassed');
    }
  });
  const initial='Seven stars over Lima!';
  await check('create and log in with a password longer than the old 20-character limit',async()=>{
    await ok('/system/user',{token:admin,method:'POST',body:{userName:username,nickName:username,password:initial,status:'0',roleIds:[],postIds:[]}});
    const rows=(await ok('/system/user/list?userName='+username,{token:admin})).rows.filter(row=>row.userName===username);
    assert.equal(rows.length,1);fixtureId=rows[0].userId;
  });
  const userToken=await login(username,initial);
  await check('self-service API rejects weak new password without changing the existing password',async()=>{
    const result=await request('/system/user/profile/updatePwd',{token:userToken,method:'PUT',body:{oldPassword:initial,newPassword:'passwordpassword'}});
    assert.equal(result.code,400);
    await login(username,initial);
  });
  const next='雨后山谷的微风与窗边温热的红茶';
  await check('self-service password change accepts a Unicode passphrase',async()=>{
    await ok('/system/user/profile/updatePwd',{token:userToken,method:'PUT',body:{oldPassword:initial,newPassword:next}});
    await login(username,next);
  });
  await check('administrator reset validates the actual account, not a supplied username',async()=>{
    const result=await request('/system/user/resetPwd',{token:admin,method:'PUT',body:{userId:fixtureId,userName:'unrelated',password:username+'123456789'}});
    assert.equal(result.code,400);
  });
  const reset='Another valley at dawn!';
  await check('administrator can reset to a valid long password',async()=>{
    await ok('/system/user/resetPwd',{token:admin,method:'PUT',body:{userId:fixtureId,password:reset}});
    await login(username,reset);
  });
  await check('general edit/status endpoints cannot bypass password validation',async()=>{
    const result=await request('/system/user',{token:admin,method:'PUT',body:{userId:fixtureId,userName:username,nickName:username,roleIds:[],postIds:[],password:'bypass'}});
    assert.notEqual(result.code,200);
    await ok('/system/user/changeStatus',{token:admin,method:'PUT',body:{userId:fixtureId,status:'0',password:'bypass'}});
    await login(username,reset);
  });
} finally {
  if(admin){
    const rows=(await ok('/system/user/list?userName='+username,{token:admin})).rows.filter(row=>row.userName===username);
    assert.ok(rows.length<=1);
    for(const row of rows)await ok('/system/user/'+row.userId,{token:admin,method:'DELETE'});
  }
  for(const token of sessions.reverse())await request('/logout',{token,method:'POST'});
  console.log(`PASS disposable user/session cleanup (${checks} checks)`);
}
