import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../../../..')
process.chdir(resolve(root,'frontend'))
const require=createRequire(resolve(root,'frontend/package.json'))
const {chromium,expect}=require('@playwright/test')
let input='';for await(const c of process.stdin)input+=c
const {apiBase}=JSON.parse(input)
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(apiBase))throw Error('Isolated backend only')
process.env.VITE_API_BASE_URL=apiBase
const {createServer}=await import(pathToFileURL(resolve(root,'frontend/node_modules/vite/dist/node/index.js')).href)
let vite,browser,stage='setup';const proof={realtime:'not implemented at T4.1; teacher roster verified over HTTP/polling'}
try{
 vite=await createServer({root:resolve(root,'frontend'),logLevel:'silent',server:{host:'localhost',port:5173,strictPort:true}});await vite.listen()
 const email=`room-browser-${randomUUID()}@example.com`,password='synthetic-browser-password'
 const req=async(path,body,token)=>{const r=await fetch(apiBase+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});if(!r.ok)throw Error('HTTP fixture failed');return r.json()}
 await req('/api/auth/register',{email,mat_khau:password,ho_ten:'Room Browser'})
 const {access_token:token}=await req('/api/auth/login',{email,mat_khau:password})
 const exam=await req('/api/exams',{tenDeThi:'Đề cho phòng',cauHoiList:[{noiDung:'Nước?',dapAnA:'H2O',dapAnB:'CO2',dapAnC:'O2',dapAnD:'N2',dapAnDung:'A'}]},token)
 browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage();const errors=[];page.on('pageerror',()=>errors.push('pageerror'))
 await page.goto('http://localhost:5173/login');await page.getByLabel('Email',{exact:true}).fill(email);await page.getByLabel('Mật khẩu',{exact:true}).fill(password);await page.getByRole('button',{name:'Đăng nhập',exact:true}).click();await expect(page).toHaveURL(/\/dashboard$/)
 stage='create-room';await page.goto(`http://localhost:5173/rooms/create?examId=${exam.idDeThi}`);await page.getByLabel('Tên lớp',{exact:true}).fill('Lớp kiểm chứng');await page.getByLabel('Thời gian làm bài (phút)',{exact:true}).fill('10');await page.getByRole('button',{name:'Tạo phòng',exact:true}).click();await expect(page).toHaveURL(/\/rooms\/[a-f0-9-]{36}\/waiting$/)
 const id=page.url().split('/')[4];const detail=await(await fetch(`${apiBase}/api/rooms/${id}`,{headers:{Authorization:`Bearer ${token}`}})).json();if(!/^[A-Z0-9]{6}$/.test(detail.maCodePhongThi))throw Error('Invalid room code')
 await expect(page.getByText(detail.maCodePhongThi,{exact:true}).first()).toBeVisible()
 stage='mobile-join';const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const student=await mobile.newPage();student.on('pageerror',()=>errors.push('pageerror'))
 await student.goto('http://localhost:5173/join');await student.getByLabel('Họ và tên',{exact:true}).fill('Thí sinh kiểm chứng');await student.getByLabel('Mã phòng thi',{exact:true}).fill(detail.maCodePhongThi);await student.getByRole('button',{name:'Vào phòng thi',exact:true}).click();await expect(student).toHaveURL(new RegExp(`/exam-room/${detail.maCodePhongThi}/waiting$`))
 const saved=await student.evaluate(()=>Object.values(sessionStorage).map(v=>{try{return JSON.parse(v)}catch{return null}}).find(v=>v?.idHs));if(!saved?.idHs||saved.maCodePhongThi!==detail.maCodePhongThi)throw Error('Session missing')
 await student.reload();await expect(student.getByText('Thí sinh kiểm chứng',{exact:true})).toBeVisible();await expect(page.getByText('Thí sinh kiểm chứng',{exact:true}).first()).toBeVisible({timeout:12000})
 await student.screenshot({path:resolve(root,'backend/target/verification/T4_1_mobile.png'),fullPage:true});await page.screenshot({path:resolve(root,'backend/target/verification/T4_1_teacher.png'),fullPage:true});if(errors.length)throw Error('Page error')
 Object.assign(proof,{status:'PASS',teacherCreated:true,mobileJoin:true,reloadSession:true,teacherHttpRoster:true,pageErrors:0})
}catch(e){Object.assign(proof,{status:'FAIL',stage,detail:'Browser room flow failed'});process.exitCode=1}
finally{if(browser)await browser.close();if(vite)await vite.close();proof.browserAndViteClosed=true;await writeFile(resolve(root,'backend/target/verification/T4_1_browser.json'),JSON.stringify(proof,null,2))}
