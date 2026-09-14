import axios from 'axios';

// 应用可能被部署在子路径下（例如 https://home.netr0.com/party/），
// 以「文档根目录」作为 API 根：绝对路径 '/api/xxx' 会被拼到该根之后，
// 于是根部署得到 '/api/xxx'，子路径部署得到 '/party/api/xxx'。
const appBase = new URL('.', document.baseURI).pathname;

const client = axios.create({
    baseURL: appBase,
    timeout: 10000
});

// 响应拦截器：可以在这里统一处理 401/403 等错误
client.interceptors.response.use(
    res => res.data,
    error => Promise.reject(error)
);

export default client;
